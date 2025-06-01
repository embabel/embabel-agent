/*
 * Copyright 2024-2025 Embabel Software, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.examples.simple.movie

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * See https://www.omdbapi.com/
 * Obtain an API key here: https://www.omdbapi.com/apikey.aspx
 */
@Component
@ConditionalOnProperty("OMDB_API_KEY")
class OmdbClient(
    private val apiKey: String = System.getenv("OMDB_API_KEY"),
) {

    private val omdbRestClient: RestClient = run {
        RestClient.builder()
            .baseUrl("http://omdbapi.com")
            .defaultHeader("Accept", "application/json")
            .build()
    }


    fun getMovieById(imdb: String): MovieResponse {
        return omdbRestClient.get()
            .uri { uriBuilder ->
                uriBuilder
                    .queryParam("apikey", apiKey)
                    .queryParam("i", imdb)
                    .build()
            }
            .retrieve()
            .body(MovieResponse::class.java)
            ?: throw RuntimeException("Failed to fetch movie data")
    }

    fun getMovieByTitle(title: String): MovieResponse? {
        return omdbRestClient.get()
            .uri { uriBuilder ->
                uriBuilder
                    .queryParam("apikey", apiKey)
                    .queryParam("t", title)
                    .build()
            }
            .retrieve()
            .body(MovieResponse::class.java)
    }

}

data class MovieResponse(
    val Title: String,
    val Year: String,
    val Rated: String,
    val Released: String,
    val Runtime: String,
    val Genre: String,
    val Director: String,
    val Writer: String,
    val Actors: String,
    val Plot: String,
    val Language: String,
    val Country: String,
    val Awards: String,
    val Poster: String,
    val Ratings: List<Rating>,
    val Metascore: String,
    val imdbRating: String,
    val imdbVotes: String,
    val imdbID: String,
    val Type: String,
    val DVD: String?,
    val BoxOffice: String?,
    val Production: String?,
    val Website: String?,
    val Response: String
)

data class Rating(
    val Source: String,
    val Value: String
)
