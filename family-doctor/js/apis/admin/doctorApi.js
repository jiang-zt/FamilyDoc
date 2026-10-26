window.doctorApi = {

    doChat: async function(bo, onEvent, signal) {
        const response = await fetch(window.appConfig.backendBaseURL + '/chat/stream', {
            method: 'POST',
            credentials: 'include',
            headers: {
                'Content-Type': 'application/json',
                'Accept': 'text/event-stream',
                'Authorization': 'Bearer ' + cookieUtils.getToken()
            },
            body: JSON.stringify(bo),
            signal: signal
        });
        if (response.status === 401) {
            cookieUtils.removeUserInfo();
            cookieUtils.removeToken();
        }
        return window.chatStream.read(response, onEvent);
    },

    doChatSync: function(bo) {
        return instance({
            url: '/chat',
            method: 'post',
            data: bo
        })
    },

    getRecords: function() {
        return instance({
            url: '/chat/records',
            method: 'get',
        })
    },

    deleteRecords: function() {
        return instance({
            url: '/chat/records',
            method: 'delete',
        })
    },

}

