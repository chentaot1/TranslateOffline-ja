#include "grammar_sampler.h"

#include <cmath>
#include <android/log.h>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "LlamaJNI", __VA_ARGS__)

namespace {

void set_logits(llama_context *ctx, std::vector<llama_token_data> &cur, int n_vocab) {
    const float *logits = llama_get_logits_ith(ctx, -1);
    cur.resize((size_t)n_vocab);
    for (llama_token i = 0; i < n_vocab; i++) {
        cur[i] = { i, logits[i], 0.0f };
    }
}

}  // namespace

void GrammarSampler::reset() {
    if (grammar_) {
        llama_sampler_free(grammar_);
        grammar_ = nullptr;
    }
}

bool GrammarSampler::rebuild(const llama_vocab *vocab, const char *grammar_str) {
    reset();
    rejections_ = 0;
    if (!grammar_str || grammar_str[0] == '\0') {
        return true;
    }

    llama_sampler *grammar = llama_sampler_init_grammar(vocab, grammar_str, "root");
    if (!grammar) {
        LOGE("Grammar failed to parse; refusing to generate unconstrained");
        return false;
    }

    grammar_ = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!grammar_) {
        llama_sampler_free(grammar);
        LOGE("Failed to create grammar chain");
        return false;
    }
    llama_sampler_chain_add(grammar_, grammar);
    return true;
}

llama_token GrammarSampler::sample(
    llama_context *ctx,
    llama_sampler *chain,
    std::vector<llama_token_data> &cur,
    int n_vocab) {

    if (!grammar_) {
        // llama_sampler_sample accepts into the chain internally; an extra llama_sampler_accept
        // here would double-advance grammar state.
        return llama_sampler_sample(chain, ctx, -1);
    }

    set_logits(ctx, cur, n_vocab);
    llama_token_data_array cur_p = { cur.data(), cur.size(), -1, false };
    llama_sampler_apply(chain, &cur_p);
    llama_token id = cur_p.data[cur_p.selected].id;

    llama_token_data single = { id, 1.0f, 0.0f };
    llama_token_data_array single_arr = { &single, 1, -1, false };
    llama_sampler_apply(grammar_, &single_arr);
    if (single_arr.data[0].logit == -INFINITY) {
        rejections_++;
        set_logits(ctx, cur, n_vocab);
        cur_p = { cur.data(), cur.size(), -1, false };
        llama_sampler_apply(grammar_, &cur_p);
        llama_sampler_apply(chain, &cur_p);
        id = cur_p.data[cur_p.selected].id;
    }

    llama_sampler_accept(grammar_, id);
    llama_sampler_accept(chain, id);
    return id;
}
