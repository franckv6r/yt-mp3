package com.fp.ytmp3.plugin;

import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Contrat stable entre l'application et l'extracteur.
 * Toute la logique NewPipe (et ses dependances) est compilee dans ce plugin :
 * l'appli ne connait que cette classe et la methode info(String).
 */
public final class ExtractorApi {

    private static boolean ready = false;

    /** Retourne un JSON: {"url":"...","title":"...","uploader":"...","duration":123}. */
    public static String info(String videoUrl) throws Exception {
        init();
        StreamingService service = NewPipe.getServiceByUrl(videoUrl);
        StreamExtractor ex = service.getStreamExtractor(videoUrl);
        ex.fetchPage();

        AudioStream best = null;
        List<AudioStream> streams = ex.getAudioStreams();
        if (streams != null) {
            for (AudioStream a : streams) {
                if (best == null || a.getAverageBitrate() > best.getAverageBitrate()) {
                    best = a;
                }
            }
        }
        if (best == null) {
            throw new IllegalStateException("Aucun flux audio trouve");
        }
        int uploadYear = 0;
        try {
            DateWrapper d = ex.getUploadDate();
            if (d != null) {
                uploadYear = d.getLocalDateTime().getYear();
            }
        } catch (Exception ignored) {
        }
        return "{\"url\":\"" + esc(best.getUrl())
                + "\",\"title\":\"" + esc(ex.getName())
                + "\",\"uploader\":\"" + esc(ex.getUploaderName())
                + "\",\"duration\":" + ex.getLength()
                + ",\"uploadYear\":" + uploadYear + "}";
    }

    /**
     * Retourne un tableau JSON d'entrees (playlist) ou un tableau a un element (video seule).
     * Parcourt toutes les pages. Garde-fou : 5000 entrees / 200 pages.
     */
    public static String list(String url) throws Exception {
        init();
        StreamingService service = NewPipe.getServiceByUrl(url);
        boolean looksPlaylist = url.contains("list=") || url.contains("/playlist");
        if (looksPlaylist) {
            try {
                PlaylistExtractor pe = service.getPlaylistExtractor(url);
                pe.fetchPage();
                StringBuilder sb = new StringBuilder("[");
                int n = 0;
                int pages = 0;
                InfoItemsPage<StreamInfoItem> page = pe.getInitialPage();
                while (page != null) {
                    for (StreamInfoItem it : page.getItems()) {
                        if (n > 0) {
                            sb.append(',');
                        }
                        sb.append("{\"url\":\"").append(esc(it.getUrl()))
                                .append("\",\"title\":\"").append(esc(it.getName()))
                                .append("\",\"uploader\":\"").append(esc(it.getUploaderName()))
                                .append("\",\"duration\":").append(it.getDuration()).append('}');
                        if (++n >= 5000) {
                            break;
                        }
                    }
                    if (n >= 5000 || !page.hasNextPage()) {
                        break;
                    }
                    Page next = page.getNextPage();
                    if (next == null || ++pages > 200) {
                        break;
                    }
                    page = pe.getPage(next);
                }
                sb.append(']');
                if (n > 0) {
                    return sb.toString();
                }
            } catch (Exception e) {
                // playlist non exploitable : on retombe sur la video
            }
        }
        StreamExtractor se = service.getStreamExtractor(url);
        se.fetchPage();
        return "[{\"url\":\"" + esc(url)
                + "\",\"title\":\"" + esc(se.getName())
                + "\",\"uploader\":\"" + esc(se.getUploaderName())
                + "\",\"duration\":" + se.getLength() + "}]";
    }

    private static synchronized void init() {
        if (ready) {
            return;
        }
        NewPipe.init(new HttpDownloader());
        ready = true;
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static final class HttpDownloader extends Downloader {
        @Override
        public Response execute(Request request) throws IOException, ReCaptchaException {
            HttpURLConnection c = (HttpURLConnection) new URL(request.url()).openConnection();
            c.setRequestMethod(request.httpMethod());
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            Map<String, List<String>> headers = request.headers();
            if (headers != null) {
                for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                    for (String v : e.getValue()) {
                        c.addRequestProperty(e.getKey(), v);
                    }
                }
            }
            // Android ajoute "Dalvik/..." comme UA, ce qui renvoie la version mobile
            // de YouTube (m.youtube.com), illisible pour NewPipe. On force un UA desktop.
            if (c.getRequestProperty("User-Agent") == null) {
                c.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                                + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
            }
            // Android decompresse mal les reponses si le header est deja present :
            // on force le texte brut, avec repli gzip ci-dessous.
            c.setRequestProperty("Accept-Encoding", "identity");
            byte[] data = request.dataToSend();
            if (data != null) {
                c.setDoOutput(true);
                c.getOutputStream().write(data);
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) {
                String enc = c.getContentEncoding();
                if (enc != null && enc.toLowerCase().contains("gzip")) {
                    in = new GZIPInputStream(in);
                }
            }
            String body = in == null ? "" : readAll(in);
            return new Response(code, c.getResponseMessage(), c.getHeaderFields(), body, request.url());
        }

        private static String readAll(InputStream in) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        }
    }

    private ExtractorApi() {
    }
}
