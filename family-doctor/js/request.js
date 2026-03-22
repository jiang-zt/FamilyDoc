
const backendBaseURL = `${window.location.protocol}//${window.location.hostname}:8080`;
window.appConfig = {
    backendBaseURL: backendBaseURL
};

// 创建axios实例
const instance = axios.create({
    // baseURL: process.env.VUE_APP_BASE_API, // url = base url + request url
    // baseURL: "http://10.0.0.3:8080", // url = base url + request url
    baseURL: backendBaseURL,
    // baseURL: "https://www.itzixi.com/api", // url = base url + request url
    withCredentials: true, // send cookies when cross-domain requests
    timeout: 5000 // request timeout
});

// axios请求的拦截器
instance.interceptors.request.use(
    config => {
        // do something before request is sent
        
        var userInfo = cookieUtils.getUserInfo();
        // console.log(userInfo);
        if (userInfo) {
            // console.log("userId = " + userInfo.id);
            config.headers['headerUserId'] = userInfo.id;
        }

        var userToken = cookieUtils.getToken();
        // console.log("userToken = " + userToken);
        if (userToken) {
            // console.log("userToken = " + userToken);
            config.headers['headerUserToken'] = userToken;
            config.headers['Authorization'] = 'Bearer ' + userToken;
        }

        return config
    },
    error => {
        // do something with request error
        console.log(error) // for debug
        return Promise.reject(error)
    }
);

// axios响应的拦截器
instance.interceptors.response.use(
    response => {
        const res = response.data
        return res;
    },
    error => {
        console.log('err: ' + error) // for debug
        console.log('err: ' + error.data) // for debug
        if (error && error.response && error.response.status === 401) {
            cookieUtils.removeUserInfo();
            cookieUtils.removeToken();
        }
        return Promise.reject(error)
    }
)
