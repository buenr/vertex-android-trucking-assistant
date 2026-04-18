package trucker.geminilive.network

import android.content.Context
import com.google.auth.oauth2.ServiceAccountCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object VertexAuth {
    private const val ASSET_NAME = "vertex-ai-testing1.json"
    private const val SCOPE = "https://www.googleapis.com/auth/cloud-platform"
    private var cachedProjectId: String? = null

    suspend fun getAccessToken(context: Context): String = withContext(Dispatchers.IO) {
        context.assets.open(ASSET_NAME).use { stream ->
            val credentials = ServiceAccountCredentials.fromStream(stream)
                .createScoped(listOf(SCOPE))
            credentials.refreshIfExpired()
            credentials.accessToken.tokenValue
        }
    }

    fun getProjectId(context: Context): String {
        cachedProjectId?.let { return it }
        return try {
            context.assets.open(ASSET_NAME).use { stream ->
                val credentials = ServiceAccountCredentials.fromStream(stream)
                val pid = credentials.projectId ?: "vertex-ai-testing1"
                cachedProjectId = pid
                pid
            }
        } catch (e: Exception) {
            "vertex-ai-testing1"
        }
    }
}




