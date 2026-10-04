package com.evanaronson.languagecheck

enum class Provider(val label: String, val keyUrl: String, val recommendedModel: String) {
    Gemini("Google Gemini", "https://aistudio.google.com/apikey", "gemini-3.5-flash-lite"),
    OpenAI("OpenAI", "https://platform.openai.com/api-keys", "gpt-6-sol"),
}
