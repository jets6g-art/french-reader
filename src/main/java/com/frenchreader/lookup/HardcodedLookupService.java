package com.frenchreader.lookup;

import org.springframework.stereotype.Service;

/** Placeholder until the Claude call lands (build step 4). Fixed content, shape-complete. */
@Service
class HardcodedLookupService implements LookupService {

    @Override
    public LookupResponse lookup(LookupRequest request) {
        return new LookupResponse(
                request.word().strip(),
                "nom masculin",
                "[hardcoded] Placeholder meaning in this context.",
                "courant",
                "[hardcoded] placeholder gloss",
                "[hardcoded] e.g. conditional present, 3rd person plural");
    }
}
