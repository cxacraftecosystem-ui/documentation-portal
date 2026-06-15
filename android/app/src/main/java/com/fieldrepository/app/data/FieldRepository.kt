package com.fieldrepository.app.data

class FieldRepository(
    private val api: FieldRepositoryApi,
    private val tokenStore: TokenStore
) {
    fun hasToken(): Boolean = !tokenStore.getToken().isNullOrBlank()

    suspend fun login(email: String, password: String): UserDto {
        val response = api.login(LoginRequest(email = email.trim(), password = password))
        tokenStore.setToken(response.accessToken)
        return response.user
    }

    suspend fun loginWithGoogle(idToken: String): UserDto {
        val response = api.googleLogin(GoogleLoginRequest(googleIdToken = idToken))
        tokenStore.setToken(response.accessToken)
        return response.user
    }

    fun logout() {
        tokenStore.setToken(null)
    }

    suspend fun currentUser(): UserDto = api.me()

    suspend fun stats(): DashboardStats = api.dashboardStats()

    suspend fun artisans(): List<ArtisanDto> = api.artisans(pageSize = 50).items

    suspend fun createArtisan(body: ArtisanCreateRequest): ArtisanDto = api.createArtisan(body)

    suspend fun createCraft(body: CraftCreateRequest) {
        api.createCraft(body)
    }

    suspend fun createWorkshop(body: WorkshopCreateRequest) {
        api.createWorkshop(body)
    }

    suspend fun createProduct(body: ProductCreateRequest) {
        api.createProduct(body)
    }

    suspend fun createTool(body: ToolCreateRequest) {
        api.createTool(body)
    }
}
