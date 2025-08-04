/**
 * @fileoverview Event listener for locale change functionality
 * @description This file sets up event listeners for the locale change button
 * and handles the navigation to different language versions of the page.
 */

/**
 * Event listener that initializes the locale change functionality
 * 
 * @description This function waits for the DOM to be fully loaded and then
 * sets up a click event listener on the 'changeLocale' button. When clicked,
 * it prevents the default action and navigates to the toggled locale URL.
 * 
 * @listens DOMContentLoaded
 * @listens click
 * 
 * @example
 * // The button with id 'changeLocale' will trigger locale switching
 * // when clicked, navigating between .en.html and .af.html versions
 * 
 * @note This function depends on the getURL() function from functions.js
 */
document.addEventListener('DOMContentLoaded', () => {
    var button = document.getElementById('changeLocale');
    if (button) {
        button.addEventListener('click', (e) => {
            e.preventDefault();
            window.location.href = getURL();
        });
    }
});