package arc.snora.player

/** ISO-639 language labels used when media containers omit a useful track title. */
object LanguageNames {
    data class Language(
        val iso2: String,
        val iso3: String,
        val fullName: String,
        val aliases: List<String> = emptyList()
    )

    // Common movie dubbing/subtitle languages, with ISO-639-1 and ISO-639-2 codes.
    val languages: List<Language> = listOf(
        Language("ar", "ara", "Arabic"), Language("bn", "ben", "Bengali"),
        Language("bg", "bul", "Bulgarian"), Language("my", "bur", "Burmese", listOf("mya")),
        Language("zh", "chi", "Chinese", listOf("zho")),
        Language("hr", "hrv", "Croatian"), Language("cs", "cze", "Czech", listOf("ces")),
        Language("da", "dan", "Danish"), Language("nl", "dut", "Dutch", listOf("nld")),
        Language("en", "eng", "English"), Language("et", "est", "Estonian"),
        Language("fi", "fin", "Finnish"), Language("fr", "fre", "French", listOf("fra")),
        Language("ka", "geo", "Georgian", listOf("kat")), Language("de", "ger", "German", listOf("deu")),
        Language("el", "gre", "Greek", listOf("ell")), Language("gu", "guj", "Gujarati"),
        Language("he", "heb", "Hebrew"), Language("hi", "hin", "Hindi"),
        Language("hu", "hun", "Hungarian"), Language("id", "ind", "Indonesian"),
        Language("it", "ita", "Italian"), Language("ja", "jpn", "Japanese"),
        Language("kn", "kan", "Kannada"), Language("ko", "kor", "Korean"),
        Language("lt", "lit", "Lithuanian"), Language("ms", "may", "Malay", listOf("msa")),
        Language("ml", "mal", "Malayalam"), Language("mr", "mar", "Marathi"),
        Language("ne", "nep", "Nepali"), Language("no", "nor", "Norwegian"),
        Language("fa", "per", "Persian", listOf("fas")), Language("pl", "pol", "Polish"),
        Language("pt", "por", "Portuguese"), Language("pa", "pan", "Punjabi"),
        Language("ro", "rum", "Romanian", listOf("ron")), Language("ru", "rus", "Russian"),
        Language("sr", "srp", "Serbian"), Language("si", "sin", "Sinhala"),
        Language("sk", "slo", "Slovak", listOf("slk")), Language("sl", "slv", "Slovenian"),
        Language("es", "spa", "Spanish"), Language("sw", "swa", "Swahili"),
        Language("sv", "swe", "Swedish"), Language("ta", "tam", "Tamil"),
        Language("te", "tel", "Telugu"), Language("th", "tha", "Thai"),
        Language("tr", "tur", "Turkish"), Language("uk", "ukr", "Ukrainian"),
        Language("ur", "urd", "Urdu"), Language("vi", "vie", "Vietnamese")
    )

    private val byCode: Map<String, Language> = buildMap {
        languages.forEach { language ->
            put(language.iso2.lowercase(), language)
            put(language.iso3.lowercase(), language)
            put(language.fullName.lowercase(), language)
            language.aliases.forEach { put(it.lowercase(), language) }
        }
    }

    fun languageFor(code: String): Language? {
        val normalized = code.trim().lowercase().substringBefore('-').substringBefore('_')
        if (normalized.isEmpty() || normalized == "und" || normalized == "unk") return null
        return byCode[normalized]
    }

    /**
     * User's 3 Exact Track Naming Rules:
     * 1st: lang matches map & code matches in title -> {lang full name}
     * 2nd: lang matches map & code does not match in title -> {lang full name}
     * 3rd: lang does NOT match map -> {title} (or fallback)
     * If multiple tracks exist for the same language, appends index to distinguish.
     */
    fun displayTrackTitle(
        title: String,
        lang: String,
        trackIndexForLanguage: Int = 1,
        totalForLanguage: Int = 1,
        fallback: String
    ): String {
        val cleanTitle = title.trim()
        val language = languageFor(lang)

        return if (language != null) {
            // Rules 1 & 2: language matched in map -> {lang full name}
            if (totalForLanguage > 1) {
                "${language.fullName} $trackIndexForLanguage"
            } else {
                language.fullName
            }
        } else {
            // Rule 3: language not matching map -> {title}
            cleanTitle.ifEmpty { fallback }
        }
    }

    fun groupingKey(lang: String): String {
        val normalized = lang.trim().lowercase().substringBefore('-').substringBefore('_')
        return byCode[normalized]?.iso3 ?: normalized
    }
}
