package com.evanaronson.languagecheck

enum class Provider(val label: String, val keyUrl: String) {
    Gemini("Google Gemini", "https://aistudio.google.com/apikey"),
    OpenAI("OpenAI", "https://platform.openai.com/api-keys"),
}
