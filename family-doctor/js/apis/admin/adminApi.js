window.adminApi = {

    listUsers: function() {
        return instance({
            url: '/auth/users',
            method: 'get',
        })
    },

    deleteUser: function(id) {
        return instance({
            url: '/auth/users/' + id,
            method: 'delete',
        })
    },

    listChatUsers: function() {
        return instance({
            url: '/auth/chat-users',
            method: 'get',
        })
    },

    listChatMetrics: function(params) {
        return instance({
            url: '/auth/chat-metrics',
            method: 'get',
            params: params
        })
    }
}
