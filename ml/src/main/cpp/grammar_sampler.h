#pragma once

#include <vector>
#include "llama.h"

// A GBNF grammar applied at sample time, held OUTSIDE the ordinary sampler chain on purpose:
// llama_grammar_apply_impl writes -INFINITY without clearing cur_p->sorted, and top_k leaves it
// true, so a grammar placed after top_k in the same chain yields NaN probabilities. Applied with
// upstream's rejection-sampling shape instead (ADR-0013).
class GrammarSampler {
public:
    ~GrammarSampler() { reset(); }

    // An empty or null grammar means "no grammar" and succeeds, leaving sampling unconstrained.
    //
    // llama_sampler_init_grammar returns NULL when the GBNF fails to parse, and
    // llama_sampler_chain_add dereferences unconditionally, so an unchecked return SIGSEGVs.
    // Failing here refuses the generation rather than silently sampling unconstrained.
    bool rebuild(const llama_vocab *vocab, const char *grammar_str);

    void reset();

    bool active() const { return grammar_ != nullptr; }
    int rejections() const { return rejections_; }

    // Sample from [chain] first, test the chosen token against the grammar through a one-element
    // array, and only on rejection re-sample with the grammar applied *before* the chain -- the
    // ordering that keeps the sorted flag honest (upstream's common/sampling.cpp). With no grammar
    // this is the shipped call unchanged.
    llama_token sample(llama_context *ctx, llama_sampler *chain, std::vector<llama_token_data> &cur, int n_vocab);

private:
    llama_sampler *grammar_ = nullptr;
    int rejections_ = 0;
};
