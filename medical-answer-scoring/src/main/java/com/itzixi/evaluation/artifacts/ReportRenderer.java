package com.itzixi.evaluation.artifacts;

import com.itzixi.evaluation.config.EvaluationConfigLoader.LoadedSuite;
import com.itzixi.evaluation.support.JsonSupport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Uses the shared Python renderer and HTML template; no second Java HTML layout. */
public final class ReportRenderer {
    public static Path sourcePath(LoadedSuite loaded) {
        var artifacts=JsonSupport.child(loaded.suite(),"artifacts");
        if (!"evaluation_directory".equals(artifacts.get("path_base")))
            throw new IllegalArgumentException("Unsupported artifact path_base");
        String source=artifacts.get("renderer_path") instanceof String value?value:"reporting/render_report.py";
        Path path=loaded.suitePath().getParent().getParent().resolve(source).normalize();
        if (!Files.isRegularFile(path) || !Files.isReadable(path)
                || !Files.isRegularFile(path.resolveSibling("report_template.html")))
            throw new IllegalArgumentException("Missing report renderer/template: "+path);
        return path;
    }

    public Path generate(Path runDirectory,Path renderer) {
        Path output=runDirectory.resolve("report.html");
        Process process=null;
        try {
            String python=System.getenv().getOrDefault("EVAL_PYTHON","python3");
            process=new ProcessBuilder(python,renderer.toAbsolutePath().toString(),runDirectory.toAbsolutePath().toString(),"--finalize")
                    .redirectErrorStream(true).redirectOutput(runDirectory.resolve("report-render.log").toFile()).start();
            if (!process.waitFor(30,TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Report renderer exceeded 30 seconds");
            }
            if (process.exitValue()!=0 || !Files.isRegularFile(output) || Files.size(output)==0)
                throw new IOException("Report renderer failed; see "+runDirectory.resolve("report-render.log"));
            return output;
        } catch (InterruptedException exception) {
            if (process!=null) process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException("Report rendering interrupted",exception));
        } catch (IOException exception) { throw new UncheckedIOException("Cannot render report using Python/template",exception); }
    }
}
