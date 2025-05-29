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
package com.embabel.examples.simple.horoscope

import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient

fun interface HoroscopeService {

    fun dailyHoroscope(sign: String): String
}

@Service
class HoroscopeAppApiHoroscopeService : HoroscopeService {

    private val restClient = RestClient.builder()
        .baseUrl("https://horoscope-app-api.vercel.app")
        .build()

    override fun dailyHoroscope(sign: String): String {
        val response = restClient.get()
            .uri("/api/v1/get-horoscope/daily?sign={sign}", sign.lowercase())
            .retrieve()
            .body(HoroscopeResponse::class.java)

        return response?.data?.horoscope_data
            ?: "Unable to retrieve horoscope for $sign today."
    }
}

private data class HoroscopeResponse(
    val success: Boolean,
    val status: Int,
    val data: HoroscopeData?
)

private data class HoroscopeData(
    val date: String,
    val horoscope_data: String
)
