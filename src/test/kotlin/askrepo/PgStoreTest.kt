package askrepo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PgStoreTest {

    @Test
    fun storeInitDefaultsToFileBackend() {
        Store.init(null)
        assertNull(Store.pg)
    }

    @Test
    fun storeFileBackendWorksWithoutDatabase() {
        Store.init(null)
        val indexes = Store.listNamedIndexes(java.nio.file.Path.of("/nonexistent"))
        assertEquals(emptyList(), indexes)
    }

    @Test
    fun storeDelegatesWhenPgSet() {
        Store.init(null)
        assertNull(Store.pg)
    }
}
