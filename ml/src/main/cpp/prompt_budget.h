#pragma once

inline bool prompt_fits(int tokens, int batch, int context, int output) {
    return tokens > 0 && output > 0 && tokens <= batch && tokens <= context - output - 8;
}
