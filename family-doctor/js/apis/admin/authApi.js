window.authApi = {

    login: function(bo) {
        return instance({
            url: '/auth/login',
            method: 'post',
            data: bo
        })
    },

    register: function(bo) {
        return instance({
            url: '/auth/register',
            method: 'post',
            data: bo
        })
    },

    me: function() {
        return instance({
            url: '/auth/me',
            method: 'get',
        })
    }
}
