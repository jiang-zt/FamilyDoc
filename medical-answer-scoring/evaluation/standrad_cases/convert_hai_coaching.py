#!/usr/bin/env python3
"""Convert HAI-coaching DATASET rows into reviewable response-generation cases.

Uses only Python's standard library; does not modify the Excel or call a model.
Exports OT=N rows with the agreed 16 source fields and 7 case fields. Unknown
scalar values are null, missing lists are [], and every case awaits review.

Run from any directory:
    python3 /path/to/1cases/convert_hai_coaching.py

Rebuilding a nonempty JSONL requires --overwrite and discards manual edits.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import posixpath
import sys
import tempfile
import unicodedata
import xml.etree.ElementTree as ET
import zipfile


HERE = Path(__file__).resolve().parent
DEFAULT_INPUT = HERE.parents[1] / 'datasets/hai-coaching.xlsx'
DEFAULT_OUTPUT = HERE / 'hai-coaching-v1.jsonl'
NS = {'m': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
RELATIONSHIP_ID = '{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id'
RESPONSE_TYPES = ['reflection', 'comfort', 'reframing', 'suggestion']


def xml_text(element):
    return ''.join(node.text or '' for node in element.findall('.//m:t', NS))


def read_rows(path):
    """Resolve DATASET by its workbook relationship, including sparse cells."""
    with zipfile.ZipFile(path) as archive:
        shared = []
        if 'xl/sharedStrings.xml' in archive.namelist():
            root = ET.fromstring(archive.read('xl/sharedStrings.xml'))
            shared = [xml_text(item) for item in root.findall('m:si', NS)]
        workbook = ET.fromstring(archive.read('xl/workbook.xml'))
        sheet = next((s for s in workbook.findall('m:sheets/m:sheet', NS)
                      if s.get('name') == 'DATASET'), None)
        if sheet is None:
            raise ValueError('Missing DATASET worksheet')
        relationships = ET.fromstring(archive.read('xl/_rels/workbook.xml.rels'))
        relationship = next((r for r in relationships
                             if r.get('Id') == sheet.get(RELATIONSHIP_ID)), None)
        if relationship is None or relationship.get('TargetMode') == 'External':
            raise ValueError('Missing local DATASET worksheet relationship')
        target = relationship.get('Target', '')
        member = posixpath.normpath(target.lstrip('/') if target.startswith('/')
                                   else posixpath.join('xl', target))
        if not member.startswith('xl/'):
            raise ValueError('Invalid DATASET worksheet path')
        root = ET.fromstring(archive.read(member))
        headers = None
        for element in root.findall('m:sheetData/m:row', NS):
            cells = {}
            for cell in element.findall('m:c', NS):
                column = ''.join(c for c in cell.get('r', '') if c.isalpha())
                value_node = cell.find('m:v', NS)
                value = value_node.text or '' if value_node is not None else ''
                if cell.get('t') == 's':
                    value = shared[int(value)]
                elif cell.get('t') == 'inlineStr':
                    value = xml_text(cell)
                cells[column] = value
            if not any(v.strip() for v in cells.values()):
                continue
            if headers is None:
                headers = {column: value.strip() for column, value in cells.items() if value.strip()}
                if len(set(headers.values())) != len(headers):
                    raise ValueError('Duplicate DATASET column names')
                if not {'struggle_original', 'OT'}.issubset(headers.values()):
                    raise ValueError('Missing struggle_original or OT column')
                continue
            yield element.get('r'), {name: cells.get(column, '')
                                     for column, name in headers.items()}
        if headers is None:
            raise ValueError('DATASET worksheet has no header')


def scalar(value):
    return value if value and value.strip() not in {'', 'N/A'} else None


def separated(value):
    if scalar(value) is None:
        return []
    # Preserve empty positions so an absent item cannot shift safety labels.
    return [scalar(part.strip()) for part in value.split('###')]


def row_to_case(row, row_number, language):
    original = scalar(row.get('struggle_original', ''))
    if original is None:
        raise ValueError(f'Excel row {row_number}: empty struggle_original')
    normalized = unicodedata.normalize('NFC', original).strip()
    identity = hashlib.sha256(normalized.encode('utf-8')).hexdigest()[:16]
    # Original wording can repeat across annotators. Keep those records distinct
    # while grouping them together; do not derive IDs from worksheet row order.
    source_identity = json.dumps([normalized, row.get('annotator', ''), row.get('doc_no', '')],
                                 ensure_ascii=False, separators=(',', ':'))
    case_identity = hashlib.sha256(source_identity.encode('utf-8')).hexdigest()[:16]
    case = {
        'case_id': f'hai_{case_identity}_{language or "unknown"}',
        'group_id': f'struggle_{identity}',
        'struggle_original': original,
        'cluster_expert': scalar(row.get('cluster_expert', '')),
        'cluster_expert_merged': scalar(row.get('cluster_expert_merged', '')),
        'OT': 'N',
    }
    for kind in RESPONSE_TYPES:
        candidates = separated(row.get(kind + '_candidates', ''))
        annotations = separated(row.get(kind + '_annotation', ''))
        if len(candidates) != len(annotations):
            raise ValueError(f'Excel row {row_number}: {kind} candidates/annotation length mismatch')
        if any(label not in {'Y', 'N', None} for label in annotations):
            raise ValueError(f'Excel row {row_number}: unknown {kind} safety label')
        case[kind + '_candidates'] = candidates
        case[kind + '_annotation'] = annotations
        case[kind + '_from_expert'] = separated(row.get(kind + '_from_expert', ''))
    case.update({
        'input': {
            'struggle': original,
            'required_response_types': list(RESPONSE_TYPES),
        },
        'expected': {
            'required_response_types': list(RESPONSE_TYPES),
            'required_behaviors': [],
            'forbidden_behaviors': [],
        },
        'evaluation': {
            'method': None,
            'rubric_version': None,
            'criteria': [],
            'pass_threshold': None,
        },
        'language': language,
        'review_status': 'pending_review',
    })
    return case


def convert(source, output, *, language='en', overwrite=False):
    source, output = source.resolve(), output.resolve()
    if source == output:
        raise ValueError('Input and output must be different files')
    if output.exists() and output.stat().st_size and not overwrite:
        raise ValueError('Output is nonempty; use --overwrite to discard existing/manual edits')
    cases, ids = [], set()
    row_count, excluded = 0, 0
    for number, row in read_rows(source):
        row_count += 1
        ot = row.get('OT', '').strip()
        if ot == 'Y':
            excluded += 1
            continue
        if ot != 'N':
            raise ValueError(f'Excel row {number}: OT must be Y or N')
        case = row_to_case(row, number, language)
        if case['case_id'] in ids:
            raise ValueError(f'Excel row {number}: duplicate original struggle/case_id')
        ids.add(case['case_id'])
        cases.append(case)
    if not cases:
        raise ValueError('No OT=N cases to export; existing output was preserved')

    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=output.parent,
                                         prefix=output.name + '.', suffix='.tmp', delete=False) as stream:
            temporary = Path(stream.name)
            for case in cases:
                stream.write(json.dumps(case, ensure_ascii=False, allow_nan=False) + '\n')
        os.replace(temporary, output)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    return {
        'source': str(source),
        'source_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
        'source_rows': row_count,
        'excluded_ot_y': excluded,
        'exported_cases': len(cases),
        'output': str(output),
        'review_status': 'pending_review',
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, default=DEFAULT_INPUT, help='Original HAI-coaching XLSX')
    parser.add_argument('--output', type=Path, default=DEFAULT_OUTPUT, help='Destination case JSONL')
    parser.add_argument('--language', default='en', help='Input language tag; does not translate text')
    parser.add_argument('--overwrite', action='store_true', help='Replace a nonempty JSONL, losing manual edits')
    args = parser.parse_args(argv)
    try:
        summary = convert(args.input, args.output, language=args.language, overwrite=args.overwrite)
    except (ValueError, OSError, KeyError, ET.ParseError, zipfile.BadZipFile) as error:
        print(f'Conversion failed: {error}', file=sys.stderr)
        return 1
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
