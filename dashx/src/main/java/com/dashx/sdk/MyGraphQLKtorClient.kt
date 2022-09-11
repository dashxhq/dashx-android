package com.dashx.sdk

import com.expediagroup.graphql.client.GraphQLClient
import com.expediagroup.graphql.client.serialization.types.KotlinxGraphQLError
import com.expediagroup.graphql.client.serialization.types.KotlinxGraphQLResponse
import com.expediagroup.graphql.client.serializer.GraphQLClientSerializer
import com.expediagroup.graphql.client.serializer.defaultGraphQLSerializer
import com.expediagroup.graphql.client.types.GraphQLClientRequest
import com.expediagroup.graphql.client.types.GraphQLClientResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import org.json.JSONObject
import java.io.Closeable
import java.net.URL

/**
 * A lightweight typesafe GraphQL HTTP client using Ktor HTTP client engine.
 */
class MyGraphQLKtorClient(
    private val url: URL,
    private val httpClient: HttpClient = HttpClient(engineFactory = CIO),
    private val serializer: GraphQLClientSerializer = defaultGraphQLSerializer()
) : GraphQLClient<HttpRequestBuilder>, Closeable {

    override suspend fun <T : Any> execute(request: GraphQLClientRequest<T>, requestCustomizer: HttpRequestBuilder.() -> Unit): GraphQLClientResponse<T> {
        try {
            val rawResult1 = httpClient.post(url) {
                expectSuccess = true
                apply(requestCustomizer)
                setBody(TextContent(serializer.serialize(request), ContentType.Application.Json))
            }

            val rawResult = rawResult1.body<String>()
            val resultJsonObject = JSONObject(rawResult)

            if (resultJsonObject.getString(DATA) != "null") {
                val dataObject = resultJsonObject.getJSONObject(DATA)
                val dataObjectKeys = dataObject.keys()

                val graphqlKey = dataObjectKeys.next()
                val graphqlDataObject = dataObject.getJSONObject(graphqlKey)
                val graphqlDataIterator = graphqlDataObject.keys()

                while (graphqlDataIterator.hasNext()) {
                    val key = graphqlDataIterator.next()
                    if (graphqlDataObject.get(key).javaClass == JSONObject::class.java) {
                        graphqlDataObject.put(key, graphqlDataObject.get(key).toString())
                    }
                }

                dataObject.put(graphqlKey, graphqlDataObject)
                resultJsonObject.put(DATA, dataObject)
            }

            return serializer.deserialize(resultJsonObject.toString(), request.responseType())
        }
        catch (e:Exception){
            return KotlinxGraphQLResponse(null, listOf(KotlinxGraphQLError(e.message ?: "")))
        }
    }

    override suspend fun execute(requests: List<GraphQLClientRequest<*>>, requestCustomizer: HttpRequestBuilder.() -> Unit): List<GraphQLClientResponse<*>> {
        val rawResult: String = httpClient.post(url) {
            expectSuccess = true
            apply(requestCustomizer)
            setBody(TextContent(serializer.serialize(requests), ContentType.Application.Json))
        }.body()
        return serializer.deserialize(rawResult, requests.map { it.responseType() })
    }

    override fun close() {
        httpClient.close()
    }

    suspend fun <T> HttpClient.requestAndCatch(
        block: suspend HttpClient.() -> T,
        errorHandler: suspend ResponseException.() -> T
    ): T = runCatching { block() }
        .getOrElse {
            when (it) {
                is ResponseException -> it.errorHandler()
                else -> throw it
            }
        }
}
