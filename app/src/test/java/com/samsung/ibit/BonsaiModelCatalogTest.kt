package com.samsung.ibit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BonsaiModelCatalogTest {
    @Test
    fun containsAllBinaryAndTernaryTextModels() {
        assertEquals(8, BonsaiModels.all.size)
        assertEquals(
            setOf("1.7B", "4B", "8B", "27B"),
            BonsaiModels.all.filter { it.weightType == "1-bit" }
                .map { it.parameterCount }
                .toSet(),
        )
        assertEquals(
            setOf("1.7B", "4B", "8B", "27B"),
            BonsaiModels.all.filter { it.weightType == "ternary" }
                .map { it.parameterCount }
                .toSet(),
        )
    }

    @Test
    fun downloadArtifactsArePinnedAndIntegrityChecked() {
        BonsaiModels.all.forEach { model ->
            assertTrue(model.fileBytes > 0L)
            assertTrue(model.sha256.matches(Regex("[0-9a-f]{64}")))
            assertFalse(model.revision.equals("main", ignoreCase = true))
            assertTrue(model.downloadUrl.startsWith("https://huggingface.co/prism-ml/"))
            assertTrue(model.downloadUrl.endsWith("/${model.fileName}"))
        }
    }

    @Test
    fun identifiersAndStorageNamesAreUnique() {
        assertEquals(BonsaiModels.all.size, BonsaiModels.all.map { it.id }.toSet().size)
        assertEquals(
            BonsaiModels.all.size,
            BonsaiModels.all.map { it.fileName }.toSet().size,
        )
    }

    @Test
    fun onlyPhoneHeavyModelsCarryExperimentalWarning() {
        assertEquals(
            setOf("binary-27b-q1_0", "ternary-27b-q2_0"),
            BonsaiModels.all.filter { it.experimentalOnAndroid }.map { it.id }.toSet(),
        )
    }
}
