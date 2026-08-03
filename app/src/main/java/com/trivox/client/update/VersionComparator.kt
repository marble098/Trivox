package com.trivox.client.update

object VersionComparator {
    fun isNewer(
        candidate: String,
        current: String
    ): Boolean {
        val candidateNumbers = numbers(candidate)
        val currentNumbers = numbers(current)
        val size = maxOf(candidateNumbers.size, currentNumbers.size)

        for (index in 0 until size) {
            val candidatePart = candidateNumbers.getOrElse(index) { 0 }
            val currentPart = currentNumbers.getOrElse(index) { 0 }
            if (candidatePart != currentPart) {
                return candidatePart > currentPart
            }
        }
        return false
    }

    private fun numbers(value: String): List<Int> =
        Regex("\\d+")
            .findAll(value.substringBefore('-'))
            .mapNotNull { it.value.toIntOrNull() }
            .toList()
}
