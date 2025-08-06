/*******************************************************************************
 * Copyright 2025 Adobe
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

/**
 * Returns URL for switching between English and Afrikaans languages
 * @name getLanguageUrl Get Language URL
 * @description Modifies the current URL to switch between English (.en.html) and Afrikaans (.af.html) locales
 * @returns {string} The new URL with the toggled locale
 * @example
 * // Current URL: https://example.com/page.en.html
 * // Returns: https://example.com/page.af.html
 */
function getLanguageUrl() {
    var url = new URL(window.location.href);
    var path = url.pathname;
    // Replace locale before .html (e.g., ".en.html", ".pt-br.html", or just ".html")
    var localeMatch = path.match(/([\/\.])([a-z]{2}(?:-[a-z]{2})?)?\.html$/i);
    var locale = localeMatch ? (localeMatch[2] || '') : '';
    if (locale === '' || locale == 'en') {
        path = path.replace(/([\/\.])([a-z]{2}(?:-[a-z]{2})?)?\.html$/i, '$1af.html');
    } else if (locale === 'af') {
        path = path.replace(/([\/\.])([a-z]{2}(?:-[a-z]{2})?)?\.html$/i, '$1en.html');
    }
    storeFormState();
    url.pathname = path;
    return url.href;
}

/**
 * @private
 * Stores the current form state to session storage
 * @name storeFormState Store Form State
 * @description Retrieves form state using guideBridge.getGuideState() and stores it in sessionStorage
 * @returns {void}
 * @note Data is stored in sessionStorage with key 'formData guideContainerPath'
 */
function storeFormState() {
    var jsonData;
    guideBridge.getGuideState({
        success : function (guideResultObj) {
            jsonData = guideResultObj.data;
        },
        error : function (guideResultObj) {
            console.log("error retrieving form data");
        }
    });
    sessionStorage.setItem(guideBridge.getAutoSaveInfo()['jcr:path'], JSON.stringify(jsonData));
}

/**
 * Retrieves and applies form data from session storage
 * @name prefillDataFromSessionStorage Prefill Data From Session Storage
 * @description Retrieves stored form data from sessionStorage and applies it to the current form
 * @returns {void}
 * @note Automatically clears stored data after retrieval
 */
function prefillDataFromSessionStorage() {
    const storedData = sessionStorage.getItem(guideBridge.getAutoSaveInfo()['jcr:path']);

    if (storedData) {
        const myData = JSON.parse(storedData);
        //console.log('Received data:', myData);

        //clear the data after retrieving it
        sessionStorage.removeItem(guideBridge.getAutoSaveInfo()['jcr:path']);
        guideBridge.setData({
            guideState : myData.guideState,
            error : function (guideResultObject) {
                console.log("error retrieving form data");
            }
        })
    }
}

/**
 * Clears stored form data from session storage
 * @name clearSessionData Clear Session Data
 * @description Removes the 'formData guideContainerPath' key from sessionStorage
 * @returns {void}
 */
function clearSessionData() {
    autoSave.stop();
    sessionStorage.removeItem(guideBridge.getAutoSaveInfo()['jcr:path']);
}

/**
 * @private
 * Starts polling storeFormState() every 5 seconds
 * @name startAutoSave Start Auto Save
 * @description Creates a polling mechanism that calls storeFormState() every 5 seconds
 * @returns {Object} Object with stop() method to stop the polling
 */
function startAutoSave() {
    var intervalId = setInterval(function() {
        storeFormState();
    }, 5000); // 5 seconds

    return {
        stop: function() {
            clearInterval(intervalId);
        }
    };
}

var autoSave = startAutoSave();