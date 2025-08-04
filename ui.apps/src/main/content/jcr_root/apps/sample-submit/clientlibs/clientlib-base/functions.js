/**
 * Toggles the locale in the current URL between English and Afrikaans
 * Stores the current form state before navigation
 * 
 * @description This function modifies the current URL to switch between English (.en.html) 
 * and Afrikaans (.af.html) locales. It automatically stores the current form state 
 * before performing the locale switch.
 * 
 * @returns {string} The new URL with the toggled locale
 * 
 * @example
 * // Current URL: https://example.com/page.en.html
 * // Returns: https://example.com/page.af.html
 * 
 * // Current URL: https://example.com/page.af.html  
 * // Returns: https://example.com/page.en.html
 */
function getURL() {
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
 * Stores the current form state to session storage
 * 
 * @description This function retrieves the current form state using guideBridge.getGuideState()
 * and stores it in sessionStorage for later retrieval. The function is asynchronous
 * and uses callbacks to handle success and error scenarios.
 * 
 * @returns {void}
 * 
 * @note This function is asynchronous. The data is stored in sessionStorage with key 'formData'
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
    sessionStorage.setItem('formData', JSON.stringify(jsonData));
}

/**
 * Retrieves and applies form data from session storage
 * 
 * @description This function retrieves previously stored form data from sessionStorage
 * and applies it to the current form using guideBridge.setData(). After successful
 * retrieval, the stored data is automatically cleared from sessionStorage.
 * 
 * @returns {void}
 * 
 * @note This function automatically clears the stored data after retrieval
 */
function prefillDataFromSessionStorage() {
    const storedData = sessionStorage.getItem('formData');
    
    if (storedData) {
        const myData = JSON.parse(storedData);
        //console.log('Received data:', myData);
        
        //clear the data after retrieving it
        sessionStorage.removeItem('formData');
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
 * 
 * @description This function removes the 'formData' key from sessionStorage,
 * effectively clearing any previously stored form state.
 * 
 * @returns {void}
 */
function clearSession() {
	sessionStorage.removeItem('formData');
}