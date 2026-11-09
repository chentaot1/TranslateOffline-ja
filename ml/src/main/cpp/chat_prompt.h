#pragma once

#include <string>
#include "chat.h"

// CAT-Translate's own format, <|user|>...</s><|assistant|>: the fallback for a GGUF with no template
// or one the Jinja engine cannot render.
std::string cat_prompt(const std::string &user_prompt);

// The user turn plus the assistant cue, rendered through the GGUF's own template by llama.cpp's
// Jinja engine (#281). Render-only: thinking off, no tools, no schema, nothing parsed back.
// [system_prompt] is the catalog entry's own system message (#287); empty sends no system turn.
// Null or failing templates fall back to cat_prompt.
std::string format_chat_prompt(const common_chat_templates *templates,
                               const std::string &system_prompt,
                               const std::string &user_prompt);
