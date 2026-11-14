package com.aismartmath.board.board

/**
 * PUT YOUR OWN API KEYS HERE. Fill any of them; leave the others as "".
 * The app tries them in this order: OpenAI key 1, OpenAI key 2, Gemini key 1, Gemini key 2,
 * and moves to the next one automatically when a key is refused or has run out.
 *
 * Gemini keys: https://aistudio.google.com/apikey
 * OpenAI keys: https://platform.openai.com/api-keys  (the OpenAI API needs credit on platform.openai.com;
 *              a ChatGPT Plus subscription does not include it)
 *
 * Careful: if your GitHub repository is public, do not commit real keys here. Use a private repository.
 */
object GeminiConfig {
    const val API_KEY_1 = ""
    const val API_KEY_2 = ""

    const val OPENAI_KEY_1 = ""
    const val OPENAI_KEY_2 = ""

    /** The OpenAI model to use. If it is refused, gpt-4o-mini is tried next. */
    const val OPENAI_MODEL = "gpt-4o"
}
