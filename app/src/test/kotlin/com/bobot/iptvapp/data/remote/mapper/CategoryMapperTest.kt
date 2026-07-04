package com.bobot.iptvapp.data.remote.mapper

import com.bobot.iptvapp.data.remote.dto.CategoryDto
import com.bobot.iptvapp.domain.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryMapperTest {

    @Test
    fun `toDomain maps string categoryId and name correctly`() {
        val dto = CategoryDto(categoryId = "42", categoryName = "News")

        val result = dto.toDomain(ContentType.LIVE)

        assertEquals("42", result.id)
        assertEquals("News", result.name)
        assertEquals(ContentType.LIVE, result.type)
    }

    @Test
    fun `toDomain preserves ContentType for VOD`() {
        val dto = CategoryDto(categoryId = "7", categoryName = "Action")

        val result = dto.toDomain(ContentType.MOVIE)

        assertEquals(ContentType.MOVIE, result.type)
    }

    @Test
    fun `toDomain preserves ContentType for SERIES`() {
        val dto = CategoryDto(categoryId = "3", categoryName = "Drama")

        val result = dto.toDomain(ContentType.SERIES)

        assertEquals(ContentType.SERIES, result.type)
    }

    @Test
    fun `list toDomain maps all entries with same ContentType`() {
        val dtos = listOf(
            CategoryDto(categoryId = "1", categoryName = "Sports"),
            CategoryDto(categoryId = "2", categoryName = "Kids"),
        )

        val results = dtos.toDomain(ContentType.LIVE)

        assertEquals(2, results.size)
        assertEquals("1", results[0].id)
        assertEquals("Sports", results[0].name)
        assertEquals("2", results[1].id)
        assertEquals("Kids", results[1].name)
        results.forEach { assertEquals(ContentType.LIVE, it.type) }
    }

    @Test
    fun `toDomain with numeric-looking string id preserves value`() {
        // FlexibleStringSerializer already coerced the int to String during JSON decode.
        // This test verifies the mapper itself passes the id through unchanged.
        val dto = CategoryDto(categoryId = "999", categoryName = "Misc")

        val result = dto.toDomain(ContentType.MOVIE)

        assertEquals("999", result.id)
    }
}
