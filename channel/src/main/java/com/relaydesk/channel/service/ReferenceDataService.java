package com.relaydesk.channel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relaydesk.channel.repo.CategoryRepository;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Reference data for desk-side pickers. */
@Service
public class ReferenceDataService {

    private final CategoryRepository categories;
    private final ObjectMapper mapper;

    public ReferenceDataService(CategoryRepository categories, ObjectMapper mapper) {
        this.categories = categories;
        this.mapper = mapper;
    }

    public List<CategoryRef> activeCategories() {
        return categories.findByActiveTrueOrderByNameAsc().stream()
                .map(c -> new CategoryRef(c.getId(), c.getName()))
                .toList();
    }

    /**
     * Precision #3: MD5 is used here ONLY as an ETag for a reference-data response. This is a
     * non-cryptographic use (HTTP response caching) — an ETag needs no collision resistance —
     * so MD5 over the canonical JSON bytes is the correct, deliberate choice.
     */
    public String categoriesEtag(List<CategoryRef> data) {
        try {
            List<CategoryRef> sorted = data == null ? List.of()
                    : data.stream().sorted(Comparator.comparing(CategoryRef::name)).toList();
            byte[] bytes = mapper.writeValueAsBytes(sorted.stream()
                    .map(c -> Map.of("id", c.id().toString(), "name", c.name()))
                    .toList());
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("cannot compute categories etag", e);
        }
    }
}
