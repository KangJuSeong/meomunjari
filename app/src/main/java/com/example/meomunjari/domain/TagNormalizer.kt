package com.example.meomunjari.domain

import com.example.meomunjari.core.model.Spot
import com.example.meomunjari.core.model.TagDictionary

/** 태그 표기를 대표 태그로 묶고, 자동완성·계절 추천을 담당한다. */
class TagNormalizer(private val dictionary: TagDictionary) {

    /** "바다 전망", "#오션뷰" → "#바다뷰" */
    fun normalize(raw: String): String {
        var tag = raw.trim().replace(" ", "")
        if (tag.isEmpty()) return tag
        if (!tag.startsWith("#")) tag = "#$tag"
        return dictionary.synonyms[tag] ?: tag
    }

    fun categories(): Map<String, List<String>> = dictionary.categories

    fun seasonal(month: Int): List<String> = dictionary.seasonal[month.toString()].orEmpty()

    /** 스팟에 실제로 붙은 태그를 사용 횟수 순으로. */
    fun popular(spots: List<Spot>, limit: Int = 12): List<String> =
        spots.flatMap { it.tags }
            .groupBy { it.tag }
            .mapValues { (_, list) -> list.sumOf { it.count } }
            .entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }

    /** 입력 중인 글자로 기존 태그를 추천한다. 동의어로 입력해도 대표 태그를 보여준다. */
    fun suggest(input: String, spots: List<Spot>, limit: Int = 6): List<String> {
        val query = input.trim().removePrefix("#").replace(" ", "")
        if (query.isEmpty()) return emptyList()
        val known = (spots.flatMap { s -> s.tags.map { it.tag } } + dictionary.categories.values.flatten()).toSet()
        val viaSynonym = dictionary.synonyms.filterKeys { it.removePrefix("#").contains(query) }.values
        return (known.filter { it.removePrefix("#").contains(query) } + viaSynonym)
            .distinct()
            .sortedBy { it.length }
            .take(limit)
    }
}
