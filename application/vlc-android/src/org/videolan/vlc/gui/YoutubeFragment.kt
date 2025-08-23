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
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.view.ActionMode
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import org.json.JSONArray
import org.videolan.tools.isStarted
import org.videolan.vlc.R
import org.videolan.vlc.interfaces.Filterable
import org.videolan.vlc.util.findCurrentFragment


class YoutubeFragment : BaseFragment(), TabLayout.OnTabSelectedListener, Filterable {
    override fun getTitle() = getString(R.string.youtube)
    private lateinit var webView: WebView
    private val TAG = "YoutubeFragment"
    private val handler = Handler(Looper.getMainLooper())
    private var contentCheckRunnable: Runnable? = null
    private var scrollRunnable: Runnable? = null
    private var scrollCount = 0
    private val maxScrollAttempts = 8
    private var isContentLoaded = false
    private var initialLoadComplete = false
    override val hasTabs = true
    private var tabLayout: TabLayout? = null
    private lateinit var viewPager: ViewPager2
    private fun getCurrentFragment() = childFragmentManager.findFragmentByTag("f" + viewPager.currentItem)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.youtube, container, false)
    }

    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?) = false

    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?) = false

    override fun onDestroyActionMode(mode: ActionMode?) {}

    override fun getFilterQuery() = try {
        (getCurrentFragment() as? Filterable)?.getFilterQuery()
    } catch (e: Exception) {
        null
    }

    override fun enableSearchOption() = (getCurrentFragment() as? Filterable)?.enableSearchOption() ?: false

    override fun onTabSelected(tab: TabLayout.Tab) {
        setFabPlayVisibility(hasFAB())
    }

    override fun onTabReselected(tab: TabLayout.Tab) {}

    override fun onTabUnselected(tab: TabLayout.Tab) {
        stopActionMode()
        //needToReopenSearch = (activity as? ContentActivity)?.isSearchViewVisible() ?: false
        //lastQuery = (activity as? ContentActivity)?.getCurrentQuery() ?: ""
        //if (isStarted()) (viewPager.findCurrentFragment(childFragmentManager) as? BaseFragment)?.stopActionMode()
    }

    override fun filter(query: String) {
        (getCurrentFragment() as? Filterable)?.filter(query)
    }

    override fun restoreList() {
        (getCurrentFragment() as? Filterable)?.restoreList()
    }

    override fun setSearchVisibility(visible: Boolean) {
        (getCurrentFragment() as? Filterable)?.setSearchVisibility(visible)
    }

    override fun allowedToExpand() = (getCurrentFragment() as? Filterable)?.allowedToExpand() ?: false
    // Interface para comunicación JavaScript-Kotlin
    inner class WebAppInterface {
        @JavascriptInterface
        fun sendVideoData(jsonData: String) {
            try {
                val videoArray = JSONArray(jsonData)
                Log.d(TAG, "Videos encontrados: ${videoArray.length()}")

                if (videoArray.length() >= 20) {
                    isContentLoaded = true
                    Log.d(TAG, "¡Suficiente contenido cargado! (${videoArray.length()} videos)")
                    processVideoData(videoArray)
                    contentCheckRunnable?.let { handler.removeCallbacks(it) }
                } else if (scrollCount < maxScrollAttempts && initialLoadComplete) {
                    Log.d(TAG, "Solo ${videoArray.length()} videos encontrados, haciendo scroll...")
                    handler.postDelayed({ performScroll() }, 1500)
                } else if (scrollCount >= maxScrollAttempts) {
                    Log.d(TAG, "Máximo de intentos alcanzado. Videos encontrados: ${videoArray.length()}")
                    processVideoData(videoArray) // Procesar lo que haya
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing video data: ${e.message}")
            }
        }

        @JavascriptInterface
        fun sendScrollStatus(success: Boolean) {
            if (success) {
                Log.d(TAG, "Scroll exitoso ($scrollCount/$maxScrollAttempts)")
                // Esperar a que cargue nuevo contenido después del scroll
                handler.postDelayed({ extractVideoDataFromWebView() }, 2500)
            } else {
                Log.d(TAG, "Scroll fallido, intentando método alternativo...")
                performAlternativeScroll()
            }
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
        webSettings.mediaPlaybackRequiresUserGesture = false
        webSettings.loadWithOverviewMode = true
        webSettings.useWideViewPort = true

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
                Log.d(TAG, "Página cargada: $url")

                if (!initialLoadComplete) {
                    initialLoadComplete = true
                    // Esperar a que el contenido se renderice completamente
                    handler.postDelayed({
                        startContentCheck()
                    }, 4000) // Más tiempo para la carga inicial
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return if (url != null && url.startsWith("http")) {
                    view?.loadUrl(url)
                    true
                } else {
                    false
                }
            }
        }

        // Cargar YouTube
        webView.loadUrl("https://m.youtube.com") // Usar versión móvil que es más simple
    }

    private fun startContentCheck() {
        contentCheckRunnable = object : Runnable {
            override fun run() {
                if (!isContentLoaded && scrollCount <= maxScrollAttempts) {
                    extractVideoDataFromWebView()
                    handler.postDelayed(this, 3000) // Verificar cada 3 segundos
                }
            }
        }
        contentCheckRunnable?.let { handler.post(it) }

        // Extraer datos inicialmente
        handler.postDelayed({ extractVideoDataFromWebView() }, 2000)
    }

    private fun extractVideoDataFromWebView() {
        val javascriptCode = """
        (function() {
            try {
                console.log('Buscando videos en YouTube...');
                
                var videos = [];
                var seenIds = new Set();
                
                // Estrategia 1: Buscar elementos de video específicos
                var videoContainers = document.querySelectorAll(
                    'ytd-rich-item-renderer, ' +
                    'ytd-video-renderer, ' +
                    'ytd-grid-video-renderer, ' +
                    'ytd-compact-video-renderer, ' +
                    '[page-subtype="home"] ytd-rich-item-renderer, ' +
                    'ytd-item-section-renderer ytd-rich-item-renderer'
                );
                
                console.log('Contenedores encontrados: ' + videoContainers.length);
                
                for (var i = 0; i < videoContainers.length; i++) {
                    var container = videoContainers[i];
                    
                    try {
                        // Extraer enlace del video
                        var videoLink = container.querySelector('a[href*="/watch?v="]');
                        if (!videoLink) continue;
                        
                        var href = videoLink.getAttribute('href');
                        if (!href) continue;
                        
                        var videoId = href.split('v=')[1].split('&')[0];
                        if (!videoId || videoId.length !== 11 || seenIds.has(videoId)) continue;
                        
                        seenIds.add(videoId);
                        
                        // Extraer título - múltiples selectores
                        var titleElement = container.querySelector('#video-title') ||
                                         container.querySelector('a#video-title') ||
                                         container.querySelector('[id*="title"]') ||
                                         container.querySelector('.yt-core-attributed-string') ||
                                         videoLink;
                        
                        var title = titleElement ? 
                            (titleElement.getAttribute('title') || 
                             titleElement.textContent || 
                             titleElement.getAttribute('aria-label') || 
                             'Sin título').toString().trim() : 
                            'Sin título';
                        
                        // Extraer canal - buscar en múltiples ubicaciones
                        var channelElement = container.querySelector('.ytd-channel-name a') ||
                                           container.querySelector('.ytd-channel-name .yt-formatted-string') ||
                                           container.querySelector('[aria-label*="channel"]') ||
                                           container.querySelector('.ytd-video-meta-block a') ||
                                           container.querySelector('yt-formatted-string.ytd-channel-name') ||
                                           container.querySelector('a.yt-simple-endpoint.yt-formatted-string');
                        
                        var channel = channelElement ? 
                            (channelElement.textContent || 
                             channelElement.getAttribute('aria-label') || 
                             'Canal desconocido').toString().trim() : 
                            'Canal desconocido';
                        
                        // Extraer vistas y duración
                        var metaContainer = container.querySelector('.ytd-video-meta-block') ||
                                          container.querySelector('.inline-metadata-item') ||
                                          container.querySelector('.ytd-rich-item-renderer .metadata') ||
                                          container.querySelector('.ytd-video-renderer .metadata');
                        
                        var views = 'Vistas no disponibles';
                        var duration = 'Duración no disponible';
                        
                        if (metaContainer) {
                            var metaItems = metaContainer.querySelectorAll('span');
                            if (metaItems.length >= 2) {
                                views = metaItems[0].textContent.trim();
                                duration = metaItems[1].textContent.trim();
                            } else if (metaItems.length === 1) {
                                // Intentar determinar si es views o duration
                                var text = metaItems[0].textContent.trim();
                                if (text.includes('vistas') || text.includes('views') || text.match(/[\d,]+/)) {
                                    views = text;
                                } else {
                                    duration = text;
                                }
                            }
                        }
                        
                        // Extraer thumbnail - buscar imágenes
                        var thumbnailElement = container.querySelector('img.yt-core-image') ||
                                             container.querySelector('yt-img-shadow img') ||
                                             container.querySelector('img.yt-core-image--loaded') ||
                                             container.querySelector('yt-image img') ||
                                             container.querySelector('#thumbnail img');
                        
                        var thumbnail = '';
                        if (thumbnailElement) {
                            thumbnail = thumbnailElement.getAttribute('src') ||
                                      thumbnailElement.getAttribute('data-src') ||
                                      thumbnailElement.getAttribute('data-thumb') ||
                                      '';
                            
                            // Si es una imagen pequeña, intentar obtener la versión HD
                            if (thumbnail.includes('hqdefault')) {
                                thumbnail = thumbnail.replace('hqdefault', 'maxresdefault');
                            } else if (thumbnail.includes('sddefault')) {
                                thumbnail = thumbnail.replace('sddefault', 'maxresdefault');
                            }
                        }
                        
                        // Extraer avatar del canal si existe
                        var avatarElement = container.querySelector('.yt-lockup-metadata-view-model-wiz__avatar img') ||
                                          container.querySelector('.yt-spec-avatar-shape__image') ||
                                          container.querySelector('yt-img-shadow[width="24"] img') ||
                                          container.querySelector('img[alt*="channel"]');
                        
                        var channelAvatar = '';
                        if (avatarElement) {
                            channelAvatar = avatarElement.getAttribute('src') ||
                                          avatarElement.getAttribute('data-src') ||
                                          '';
                        }
                        
                        var videoData = {
                            id: videoId,
                            title: title,
                            channel: channel,
                            views: views,
                            duration: duration,
                            thumbnail: thumbnail,
                            channelAvatar: channelAvatar,
                            url: 'https://www.youtube.com/watch?v=' + videoId,
                            timestamp: new Date().toISOString()
                        };
                        
                        videos.push(videoData);
                        
                    } catch (e) {
                        console.error('Error procesando contenedor:', e);
                    }
                }
                
                // Estrategia 2: Buscar por enlaces directos (backup)
                if (videos.length < 20) {
                    var directLinks = document.querySelectorAll('a[href*="/watch?v="]');
                    for (var j = 0; j < directLinks.length && videos.length < 30; j++) {
                        var link = directLinks[j];
                        var href = link.getAttribute('href');
                        if (!href) continue;
                        
                        var videoId = href.split('v=')[1].split('&')[0];
                        if (!videoId || videoId.length !== 11 || seenIds.has(videoId)) continue;
                        
                        seenIds.add(videoId);
                        
                        var title = link.getAttribute('title') || 
                                  link.textContent || 
                                  link.querySelector('img')?.getAttribute('alt') || 
                                  'Video sin título';
                        
                        // Buscar información del canal en elementos cercanos
                        var parent = link.closest('ytd-rich-item-renderer, ytd-video-renderer');
                        var channel = parent ? 
                            (parent.querySelector('.ytd-channel-name')?.textContent || 'Canal desconocido') : 
                            'Canal desconocido';
                        
                        videos.push({
                            id: videoId,
                            title: title.toString().trim(),
                            channel: channel.toString().trim(),
                            views: 'Vistas no disponibles',
                            duration: 'Duración no disponible',
                            thumbnail: '',
                            channelAvatar: '',
                            url: 'https://www.youtube.com/watch?v=' + videoId,
                            timestamp: new Date().toISOString()
                        });
                    }
                }
                
                console.log('Total de videos únicos encontrados: ' + videos.length);
                AndroidInterface.sendVideoData(JSON.stringify(videos));
                
            } catch (error) {
                console.error('Error en la extracción:', error);
                AndroidInterface.sendVideoData('[]');
            }
        })();
    """.trimIndent()

        webView.evaluateJavascript(javascriptCode, null)
    }
    private fun performScroll() {
        if (scrollCount >= maxScrollAttempts || isContentLoaded) {
            return
        }

        scrollCount++
        Log.d(TAG, "Intentando scroll ($scrollCount/$maxScrollAttempts)")

        val scrollCode = """
            (function() {
                try {
                    // Intentar diferentes métodos de scroll
                    var scrollHeight = document.documentElement.scrollHeight;
                    var clientHeight = document.documentElement.clientHeight;
                    
                    // Scroll suave hasta el fondo
                    window.scrollTo({
                        top: scrollHeight,
                        behavior: 'smooth'
                    });
                    
                    // También intentar hacer click en elementos de paginación si existen
                    var moreButton = document.querySelector('ytd-button-renderer[is-pagination-button]');
                    if (moreButton) {
                        moreButton.click();
                        console.log('Botón de paginación clickeado');
                    }
                    
                    AndroidInterface.sendScrollStatus(true);
                    return true;
                } catch (e) {
                    console.error('Error en scroll:', e);
                    AndroidInterface.sendScrollStatus(false);
                    return false;
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(scrollCode, null)
    }

    private fun performAlternativeScroll() {
        val alternativeScrollCode = """
            (function() {
                try {
                    // Método alternativo: simular scroll con intervalos
                    var currentPosition = window.pageYOffset;
                    var scrollAmount = window.innerHeight * 0.8;
                    
                    window.scrollTo(0, currentPosition + scrollAmount);
                    console.log('Scroll alternativo realizado');
                    return true;
                } catch (e) {
                    console.error('Error en scroll alternativo:', e);
                    return false;
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(alternativeScrollCode) {
            handler.postDelayed({ extractVideoDataFromWebView() }, 2000)
        }
    }

    private fun processVideoData(videoArray: JSONArray) {
        val videos = mutableListOf<YouTubeVideo>()

        for (i in 0 until videoArray.length()) {
            try {
                val videoObj = videoArray.getJSONObject(i)
                val video = YouTubeVideo(
                    id = videoObj.optString("id", ""),
                    title = videoObj.optString("title", "Sin título"),
                    channel = videoObj.optString("channel", "Canal desconocido"),
                    views = videoObj.optString("views", "Vistas no disponibles"),
                    thumbnail = videoObj.optString("thumbnail", ""),
                    url = videoObj.optString("url", "")
                )
                if (video.id.isNotBlank() && video.url.isNotBlank()) {
                    videos.add(video)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing video object: ${e.message}")
            }
        }

        Log.d(TAG, "=== VIDEOS ENCONTRADOS (${videos.size}) ===")
        videos.forEachIndexed { index, video ->
            Log.d(TAG, "${index + 1}. ${video.title}")
            Log.d(TAG, "   Canal: ${video.channel}")
            Log.d(TAG, "   URL: ${video.url}")
            Log.d(TAG, "   ---")
        }

        // Aquí puedes usar la lista de videos
        if (videos.isNotEmpty()) {
            // Notificar que tenemos videos
        }
    }

    data class YouTubeVideo(
        val id: String,
        val title: String,
        val channel: String,
        val views: String,
        val thumbnail: String,
        val url: String
    )

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        contentCheckRunnable?.let { handler.removeCallbacks(it) }
        scrollRunnable?.let { handler.removeCallbacks(it) }
    }

    override fun onDestroyView() {
        contentCheckRunnable?.let { handler.removeCallbacks(it) }
        scrollRunnable?.let { handler.removeCallbacks(it) }
        webView.destroy()
        super.onDestroyView()
    }

    fun canGoBack(): Boolean = webView.canGoBack()
    fun goBack() = webView.goBack()
}