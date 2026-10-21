package com.frenchreader.lookup;

import java.util.function.BiConsumer;

public interface LookupService {
    LookupResponse lookup(LookupRequest request);

    /**
     * Same answer as {@link #lookup(LookupRequest)}, but {@code onField(name, value)} is called as
     * soon as each field is complete, so a caller can show the answer while the model is still
     * writing it. Implementations that cannot stream just return the finished answer.
     */
    default LookupResponse lookup(LookupRequest request, BiConsumer<String, String> onField) {
        return lookup(request);
    }
}
