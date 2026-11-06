package app.codeg.android.core.data

import app.codeg.android.core.model.HealthResponse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshHealthTest {
    @Test fun `only an explicit healthy nonempty payload passes the startup gate`() {
        requireHealthy(HealthResponse("ok", "1.0.0"))
        listOf(HealthResponse("error", "1.0.0"), HealthResponse("", "1.0.0"), HealthResponse("ok", " ")).forEach {
            assertTrue(runCatching { requireHealthy(it) }.isFailure)
        }
    }
}
