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
package com.embabel.examples.simple.horoscope;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class HoroscopeService implements HoroscopeServiceOperations {
    
    private final RestClient restClient = RestClient.builder()
            .baseUrl("https://horoscope-app-api.vercel.app")
            .build();

    @Override
    public String dailyHoroscope(String sign) {
        HoroscopeResponse response = restClient.get()
                .uri("/api/v1/get-horoscope/daily?sign={sign}", sign.toLowerCase())
                .retrieve()
                .body(HoroscopeResponse.class);
        
        if (response != null && response.data() != null) {
            return response.data().horoscope_data();
        }
        
        return "Unable to retrieve horoscope for " + sign + " today.";
    }
}

record HoroscopeResponse(
        boolean success,
        int status,
        HoroscopeData data
) {}

record HoroscopeData(
        String date,
        String horoscope_data
) {}