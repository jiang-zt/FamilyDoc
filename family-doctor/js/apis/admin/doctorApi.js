window.doctorApi = {

    doChat: function(bo) {
        return instance({
            url: '/chat/stream',
            method: 'post',
            data: bo
        })
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

