// 本地开发配置：file:// 页面连接 localhost:8080；静态服务器页面连接同主机 8080。
// 部署时 Nginx 会在此 URL 返回生产配置，不需要改动此文件。
window.familyDoctorConfig = {
    environment: 'development',
    backendBaseURL: 'http://' + (window.location.hostname || 'localhost') + ':8080'
};
