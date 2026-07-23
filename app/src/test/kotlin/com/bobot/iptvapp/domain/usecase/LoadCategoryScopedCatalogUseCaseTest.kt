package com.bobot.iptvapp.domain.usecase

import com.bobot.iptvapp.domain.model.Category
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.util.Resource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadCategoryScopedCatalogUseCaseTest {

    private val useCase = LoadCategoryScopedCatalogUseCase()

    private fun category(id: String, name: String = "Category $id", type: ContentType = ContentType.LIVE) =
        Category(id = id, name = name, type = type)

    @Test
    fun `categories error is forwarded to itemsState without fetching any category`() = runTest {
        val error = Resource.Error(message = "boom")
        val categoriesFlow = flowOf<Resource<List<Category>>>(error)
        val itemsState = MutableStateFlow<Resource<List<String>>>(Resource.Loading)
        var fetchCalls = 0

        useCase.invoke<String>(
            categoriesFlow = categoriesFlow,
            itemsState = itemsState,
        ) { categoryId ->
            fetchCalls++
            Resource.Success(listOf("item-$categoryId"))
        }

        assertEquals(error, itemsState.value)
        assertEquals(0, fetchCalls)
    }

    @Test
    fun `zero categories resolves to empty success and invokes onCategoriesResolved with empty list`() = runTest {
        val categoriesFlow = flowOf<Resource<List<Category>>>(Resource.Success(emptyList()))
        val itemsState = MutableStateFlow<Resource<List<String>>>(Resource.Loading)
        val resolvedCategories = mutableListOf<List<Category>>()

        useCase.invoke<String>(
            categoriesFlow = categoriesFlow,
            itemsState = itemsState,
            onCategoriesResolved = { resolvedCategories.add(it) },
        ) { _ -> Resource.Success(emptyList()) }

        assertEquals(Resource.Success(emptyList<String>()), itemsState.value)
        assertEquals(1, resolvedCategories.size)
        assertTrue(resolvedCategories.single().isEmpty())
    }

    @Test
    fun `multiple categories accumulate progressively in order`() = runTest {
        val categories = listOf(category("1"), category("2"), category("3"))
        val categoriesFlow = flowOf<Resource<List<Category>>>(Resource.Success(categories))
        val itemsState = MutableStateFlow<Resource<List<String>>>(Resource.Loading)
        val emissions = mutableListOf<Resource<List<String>>>()

        // Record every itemsState emission, in order, by collecting via manual snapshots taken
        // inside fetchCategoryItems (called synchronously by the use-case's sequential loop).
        useCase.invoke<String>(
            categoriesFlow = categoriesFlow,
            itemsState = itemsState,
        ) { categoryId ->
            val result = Resource.Success(listOf("item-$categoryId"))
            emissions.add(itemsState.value)
            result
        }
        emissions.add(itemsState.value)

        assertEquals(Resource.Success(emptyList<String>()), emissions[0])
        assertEquals(Resource.Success(listOf("item-1")), emissions[1])
        assertEquals(Resource.Success(listOf("item-1", "item-2")), emissions[2])
        assertEquals(Resource.Success(listOf("item-1", "item-2", "item-3")), emissions[3])
        assertEquals(Resource.Success(listOf("item-1", "item-2", "item-3")), itemsState.value)
    }

    @Test
    fun `a category fetch error is ignored and the loop continues, republishing on every iteration`() = runTest {
        val categories = listOf(category("1"), category("2"), category("3"))
        val categoriesFlow = flowOf<Resource<List<Category>>>(Resource.Success(categories))
        val itemsState = MutableStateFlow<Resource<List<String>>>(Resource.Loading)
        val emissions = mutableListOf<Resource<List<String>>>()

        useCase.invoke<String>(
            categoriesFlow = categoriesFlow,
            itemsState = itemsState,
        ) { categoryId ->
            val result = if (categoryId == "2") {
                Resource.Error(message = "category 2 failed")
            } else {
                Resource.Success(listOf("item-$categoryId"))
            }
            emissions.add(itemsState.value)
            result
        }
        emissions.add(itemsState.value)

        assertEquals(Resource.Success(emptyList<String>()), emissions[0])
        assertEquals(Resource.Success(listOf("item-1")), emissions[1])
        // Category 2 failed: accumulator unchanged, but itemsState still republished.
        assertEquals(Resource.Success(listOf("item-1")), emissions[2])
        assertEquals(Resource.Success(listOf("item-1", "item-3")), emissions[3])
        assertEquals(Resource.Success(listOf("item-1", "item-3")), itemsState.value)
    }

    @Test
    fun `onCategoriesResolved is invoked exactly once with the resolved categories`() = runTest {
        val categories = listOf(category("1"), category("2"))
        val categoriesFlow = flowOf<Resource<List<Category>>>(Resource.Success(categories))
        val itemsState = MutableStateFlow<Resource<List<String>>>(Resource.Loading)
        val resolvedCalls = mutableListOf<List<Category>>()

        useCase.invoke<String>(
            categoriesFlow = categoriesFlow,
            itemsState = itemsState,
            onCategoriesResolved = { resolvedCalls.add(it) },
        ) { categoryId -> Resource.Success(listOf("item-$categoryId")) }

        assertEquals(1, resolvedCalls.size)
        assertEquals(categories, resolvedCalls.single())
    }
}
