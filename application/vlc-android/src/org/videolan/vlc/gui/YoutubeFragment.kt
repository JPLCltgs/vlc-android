/*
 * ************************************************************************
 *  YoutubeFragment.kt
 * *************************************************************************
 * Copyright © 2020 VLC authors and VideoLAN
 * Author: Nicolas POMEPUY
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 * **************************************************************************
 *
 *
 */

package org.videolan.vlc.gui

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment
import org.videolan.vlc.R

class YoutubeFragment : Fragment(R.layout.youtube) {

    private lateinit var webView: WebView
    private val TAG = "YoutubeFragment"

    // Interface para comunicación JavaScript-Kotlin
    inner class WebAppInterface {
        @JavascriptInterface
        fun sendHtmlContent(html: String) {
            // Este método será llamado desde JavaScript
            Log.d(TAG, "HTML recibido: ${html.length} caracteres")
            // Aquí puedes procesar el HTML como necesites
            processYouTubeHtml(html)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        webView = view.findViewById(R.id.youtube_webview)

        // Configurar el WebView
        val webSettings: WebSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.databaseEnabled = true

        // Habilitar cookies
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // Añadir interface JavaScript
        webView.addJavascriptInterface(WebAppInterface(), "AndroidInterface")

        // Configurar WebViewClient
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Cuando la página termine de cargar, extraer el HTML
                extractHtmlFromWebView()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url != null) {
                    view?.loadUrl(url)
                }
                return true
            }
        }

        // Cargar YouTube
        webView.loadUrl("https://www.youtube.com")
    }

    // Método para extraer HTML usando JavaScript
    private fun extractHtmlFromWebView() {
        val javascriptCode = """
            (function() {
                // Obtener el HTML completo
                var htmlContent = document.documentElement.outerHTML;
                
                // Enviar a Kotlin
                AndroidInterface.sendHtmlContent(htmlContent);
                
                // También puedes extraer información específica
                var title = document.title;
                var videos = document.querySelectorAll('ytd-rich-item-renderer, ytd-video-renderer');
                
                console.log('Título: ' + title);
                console.log('Número de videos encontrados: ' + videos.length);
                
                // Enviar información específica
                var videoData = [];
                videos.forEach(function(video, index) {
                    if (index < 10) { // Limitar a los primeros 10 videos
                        var titleElem = video.querySelector('#video-title');
                        var channelElem = video.querySelector('.ytd-channel-name a');
                        var viewsElem = video.querySelector('.inline-metadata-item');
                        var thumbnailElem = video.querySelector('img');
                        
                        if (titleElem) {
                            videoData.push({
                                title: titleElem.textContent.trim(),
                                channel: channelElem ? channelElem.textContent.trim() : 'N/A',
                                views: viewsElem ? viewsElem.textContent.trim() : 'N/A',
                                thumbnail: thumbnailElem ? thumbnailElem.src : 'N/A',
                                url: titleElem.href || 'N/A'
                            });
                        }
                    }
                });
                
                // Enviar datos estructurados
                AndroidInterface.sendVideoData(JSON.stringify(videoData));
                
            })();
        """.trimIndent()

        webView.evaluateJavascript(javascriptCode, null)
    }

    // Procesar el HTML recibido
    private fun processYouTubeHtml(html: String) {
        // Aquí puedes analizar el HTML como necesites
        Log.d(TAG, "HTML completo recibido")

        // Ejemplo: Buscar títulos de videos
        val videoTitles = extractVideoTitles(html)
        Log.d(TAG, "Encontrados ${videoTitles.size} títulos de video")

        // Puedes hacer más procesamiento aquí...
    }

    // Ejemplo de extracción de títulos de videos
    private fun extractVideoTitles(html: String): List<String> {
        val titles = mutableListOf<String>()
        val pattern = """aria-label="([^"]*)"[^>]*id="video-title"""".toRegex()
        val matches = pattern.findAll(html)

        matches.forEach { matchResult ->
            titles.add(matchResult.groupValues[1])
        }

        return titles
    }

    // Método para extraer HTML en cualquier momento
    fun getCurrentHtml(callback: (String) -> Unit) {
        val javascriptCode = """
            (function() {
                return document.documentElement.outerHTML;
            })();
        """.trimIndent()

        webView.evaluateJavascript(javascriptCode) { result ->
            // El resultado viene entre comillas, necesitamos limpiarlo
            val cleanResult = result.removeSurrounding("\"")
            callback(cleanResult)
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onDestroyView() {
        webView.destroy()
        super.onDestroyView()
    }

    fun canGoBack(): Boolean = webView.canGoBack()
    fun goBack() = webView.goBack()
}