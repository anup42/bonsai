package com.samsung.ibit

data class BonsaiModel(
    val id: String,
    val displayName: String,
    val parameterCount: String,
    val weightType: String,
    val quantization: String,
    val repository: String,
    val revision: String,
    val fileName: String,
    val fileBytes: Long,
    val sha256: String,
    val experimentalOnAndroid: Boolean = false,
) {
    val downloadUrl: String
        get() = "https://huggingface.co/$repository/resolve/$revision/$fileName"

    val formatLabel: String
        get() = "$weightType $quantization"
}

object BonsaiModels {
    val all: List<BonsaiModel> = listOf(
        BonsaiModel(
            id = "binary-1.7b-q1_0",
            displayName = "Bonsai 1.7B",
            parameterCount = "1.7B",
            weightType = "1-bit",
            quantization = "Q1_0",
            repository = "prism-ml/Bonsai-1.7B-gguf",
            revision = "61edf31",
            fileName = "Bonsai-1.7B-Q1_0.gguf",
            fileBytes = 248_302_272L,
            sha256 = "3d7c6c90dd98717a203adb22d5eacd2581850e40aa5327e144b97766cae5f7e3",
        ),
        BonsaiModel(
            id = "binary-4b-q1_0",
            displayName = "Bonsai 4B",
            parameterCount = "4B",
            weightType = "1-bit",
            quantization = "Q1_0",
            repository = "prism-ml/Bonsai-4B-gguf",
            revision = "5540e20",
            fileName = "Bonsai-4B-Q1_0.gguf",
            fileBytes = 572_270_624L,
            sha256 = "4524b3f997f0f06444e568d1f26e2efd69effa3218c7ad3047432fb171e42168",
        ),
        BonsaiModel(
            id = "binary-8b-q1_0",
            displayName = "Bonsai 8B",
            parameterCount = "8B",
            weightType = "1-bit",
            quantization = "Q1_0",
            repository = "prism-ml/Bonsai-8B-gguf",
            revision = "48516770dd04643643e9f9019a2a349cf26c5dbd",
            fileName = "Bonsai-8B-Q1_0.gguf",
            fileBytes = 1_158_654_496L,
            sha256 = "284a335aa3fb2ced3b1b01fcb40b08aa783e3b70832767f0dd2e3fdfa134bd54",
        ),
        BonsaiModel(
            id = "binary-27b-q1_0",
            displayName = "Bonsai 27B",
            parameterCount = "27B",
            weightType = "1-bit",
            quantization = "Q1_0",
            repository = "prism-ml/Bonsai-27B-gguf",
            revision = "0469926cd8878dbddb5b883740ab3df060058696",
            fileName = "Bonsai-27B-Q1_0.gguf",
            fileBytes = 3_803_452_480L,
            sha256 = "17ef842e47450caeb8eaa3ebfbbab5d2f2278b62b79be107985fb69a2f819aa0",
            experimentalOnAndroid = true,
        ),
        BonsaiModel(
            id = "ternary-1.7b-q2_0",
            displayName = "Ternary Bonsai 1.7B",
            parameterCount = "1.7B",
            weightType = "ternary",
            quantization = "Q2_0",
            repository = "prism-ml/Ternary-Bonsai-1.7B-gguf",
            revision = "cd31c84cdd826a33dd297ceac6e66e08dcd8f253",
            fileName = "Ternary-Bonsai-1.7B-Q2_0.gguf",
            fileBytes = 463_290_464L,
            sha256 = "d97d94eb564590c9f0300e54d3f87bbbb25a78693d0ade9f6e177973dcb8228a",
        ),
        BonsaiModel(
            id = "ternary-4b-q2_0",
            displayName = "Ternary Bonsai 4B",
            parameterCount = "4B",
            weightType = "ternary",
            quantization = "Q2_0",
            repository = "prism-ml/Ternary-Bonsai-4B-gguf",
            revision = "c98e0e78bac494b090e025e955690f058caafe0a",
            fileName = "Ternary-Bonsai-4B-Q2_0.gguf",
            fileBytes = 1_074_969_344L,
            sha256 = "4e0bf8b737b0431552f8c2c97695ab7c0cb214c94bcdeb4f5f267e67ddf28b8b",
        ),
        BonsaiModel(
            id = "ternary-8b-q2_0",
            displayName = "Ternary Bonsai 8B",
            parameterCount = "8B",
            weightType = "ternary",
            quantization = "Q2_0",
            repository = "prism-ml/Ternary-Bonsai-8B-gguf",
            revision = "080e3f3",
            fileName = "Ternary-Bonsai-8B-Q2_0.gguf",
            fileBytes = 2_182_184_672L,
            sha256 = "3c8d70470a5d97e5a2b9410ddd899cb740116591462626c60cb2fead6448f60b",
        ),
        BonsaiModel(
            id = "ternary-27b-q2_0",
            displayName = "Ternary Bonsai 27B",
            parameterCount = "27B",
            weightType = "ternary",
            quantization = "Q2_0",
            repository = "prism-ml/Ternary-Bonsai-27B-gguf",
            revision = "3f8cc399dde45ac0475d023634974407af34907c",
            fileName = "Ternary-Bonsai-27B-Q2_0.gguf",
            fileBytes = 7_165_121_600L,
            sha256 = "868c11714cf8fe47f5ec9eeb2be0ab1a337112886f92ee0ede6b855c4fa31757",
            experimentalOnAndroid = true,
        ),
    )

    val default: BonsaiModel = all.first { it.id == "binary-8b-q1_0" }

    fun find(id: String?): BonsaiModel? = all.firstOrNull { it.id == id }
}
