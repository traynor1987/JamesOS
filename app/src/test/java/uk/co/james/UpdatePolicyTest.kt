package uk.co.james
import org.junit.Test
import org.junit.Assert.*
import uk.co.james.updates.UpdatePolicy

class UpdatePolicyTest {
    private val repo="traynor1987/JamesOS"
    @Test fun authorizesOnlySelectedRepositoryApi() {
        assertTrue(UpdatePolicy.canAuthorize(UpdatePolicy.asset(repo,123),repo))
        assertFalse(UpdatePolicy.canAuthorize("https://api.github.com/repos/traynor1987/Other/releases",repo))
        assertFalse(UpdatePolicy.canAuthorize("https://api.github.com/repos/traynor1987/JamesOS-copy/releases",repo))
    }
    @Test fun redirectsNeverCarryTokenToAssetHost() {
        assertFalse(UpdatePolicy.canAuthorize("https://release-assets.githubusercontent.com/file.apk",repo))
        assertFalse(UpdatePolicy.canAuthorize("https://api.github.com.evil.example/repos/$repo/releases",repo))
    }
    @Test fun unsafeUrlsAndEscapedPathsAreRejected() {
        listOf("http://api.github.com/repos/$repo/releases","https://user@api.github.com/repos/$repo/releases","https://api.github.com:8443/repos/$repo/releases","https://api.github.com/repos/$repo/../Other/releases","https://api.github.com/repos/$repo/%2e%2e/releases").forEach {assertFalse(UpdatePolicy.canAuthorize(it,repo))}
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsInvalidAssetId(){UpdatePolicy.asset(repo,0)}
    @Test(expected=IllegalArgumentException::class) fun rejectsInjectedRepository(){UpdatePolicy.repository("traynor1987/JamesOS/../other")}
}
