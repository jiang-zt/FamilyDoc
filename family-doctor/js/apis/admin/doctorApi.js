window.doctorApi = {

    doChat: function(bo) {
        return instance({
            url: '/ollama/chat/stream',
            method: 'post',
            data: bo
        })
    },

    doChatSync: function(bo) {
        return instance({
            url: '/ollama/chat',
            method: 'post',
            data: bo
        })
    },

    getRecords: function() {
        return instance({
            url: '/ollama/records',
            method: 'get',
        })
    },

    deleteRecords: function() {
        return instance({
            url: '/ollama/records',
            method: 'delete',
        })
    },

}

