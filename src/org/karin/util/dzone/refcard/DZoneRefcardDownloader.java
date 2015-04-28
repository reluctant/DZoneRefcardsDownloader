package org.karin.util.dzone.refcard;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringReader;
import java.io.Writer;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DZoneRefcardDownloader {
    
    private static final Pattern NUMBER_REGEX = 
            Pattern.compile("refcardzNode\\[\"number\"\\] = \"(\\d+)\"");
    
    private static final Pattern TITLE_REGEX = 
            Pattern.compile("refcardzNode\\[\"title\"\\] = \"([^\"]+)\"");
    
    private static final Pattern ID_REGEX = 
            Pattern.compile("refcardzNodes\\[\"(\\d+)\"\\] = refcardzNode;");
    
    private static final String DOWNLOAD_LINK = "/assets/request/refcard/%1$d?oid=rchom%1$d&direct=true";
    
    private static final String DIRECT_DOWNLOAD_LINK = "http://cdn.dzone.com/sites/all/files/refcardz/%s";
    
    private static final String PDF_OUTPUT_FD = "pdf";
    
    private static final String CACHE_FILE = "refcardMap.csv";
    
    private static final String HOST = "refcardz.dzone.com";
    
    
    private static final String PROXY = "proxygate2.nic.nec.co.jp";
    
    private static final int PROXY_PORT = 8080;
    
    private static final String UTF8 = "UTF-8";
    
    private boolean useProxy = false;
    
    private boolean logined = false;
    
    private Map<Integer, Refcard> refcardMap;
    
    private List<Refcard> refcards;
    
    private List<Refcard> newRefcards = new ArrayList<>();
    
    private static class Action implements AutoCloseable {
        
        private static Pattern TITLE_PTN = 
                Pattern.compile("<title>(.*)</title>", Pattern.CASE_INSENSITIVE);
        
        
        private static Proxy proxy;    
        
        private HttpURLConnection con;
        
        private int rc;
        
        private String html;
        
        private byte[] binary;
        
        public Action(String string) throws IOException {
            
            
            String host = HOST;
            String path = string;
            
            // check host
            if (string.contains("http://")) {
                // first use url to split the string url
                URL url = new URL(string);
                host = url.getHost();
                path = url.getPath();
            }
            
            URL url;
            try {
                // use uri to encode
                URI uri = new URI("http", 
                        null,  host,
                        -1, path, 
                        null, null);
                url = uri.toURL();
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }
            con = (HttpURLConnection) (
                    proxy != null ? url.openConnection(proxy) : url.openConnection());
        }
        
        public static void setProxy(Proxy proxy) {
            Action.proxy = proxy;
        }
        
        public static void setFollowRedirect(boolean follow) {
            HttpURLConnection.setFollowRedirects(follow);
        }
        
        public Action get() throws IOException {
            con.setRequestMethod("GET");
            con.setDoOutput(false);
            return doAction();
        }
        
        public Action post(String... keyvalues) throws IOException {
            int len = keyvalues.length;
            if (len % 2 != 0) {
                throw new IllegalArgumentException();
            }
            con.setRequestMethod("POST");
            if (len > 0) {
                con.setDoOutput(true);
                try (PrintWriter writer = 
                        new PrintWriter(con.getOutputStream())) {
                    for (int i = 0; i < len; i += 2) {
                        if (i > 0) {
                            writer.write('&');
                        }
                        writer.write(URLEncoder.encode(keyvalues[i], UTF8));
                        writer.write('=');
                        writer.write(URLEncoder.encode(keyvalues[i + 1], UTF8));
                    }
                    writer.close();
                }
            } else {
                con.setDoOutput(false);
            }
            
            return doAction();
        }
        
        public Action doAction() throws IOException {
            rc = con.getResponseCode();
            return this;
        }
        
        public String getHeader(String header) throws IOException {
            return con.getHeaderField(header);
        }
        
        public void close() {
            con.disconnect();
        }
        
        public int getRc() {
            return rc;
        }
        
        public String getContentType() throws IOException {
            if (rc == 0) {
                doAction();
            }
            return con.getHeaderField("Content-Type");
        }
        
        public boolean isHtmlResponse() throws IOException {
            String contentType = getContentType();
            return contentType != null && 
                    contentType.startsWith("text/html");
        }
        
        public boolean isBinaryReponse() throws IOException {
            String contentType = getContentType();
            return contentType != null && 
                    contentType.startsWith("application/octet-stream");
        }
        
        private byte[] getContent() throws IOException {
            byte[] buf = new byte[1024 * 4];
            int len;
            
            ByteArrayOutputStream res = null;
            try (InputStream ins = con.getInputStream()) {
                try {
                    res = new ByteArrayOutputStream();
                    while ((len = ins.read(buf)) > 0) {
                        res.write(buf, 0, len);
                    }
                } finally {
                    if (res != null) {
                        res.close();
                    }
                }
            }
            
            return res.toByteArray();
        }
        
        public String getHtml() throws IOException {
            if (rc == 0) {
                doAction();
            }
            
            if (html == null && isHtmlResponse()) {
                html = new String(getContent(), UTF8);
            }
            
            return html;
        }
        
        public byte[] getBinary() throws IOException {
            if (rc == 0) {
                doAction();
            }
            
            if (binary == null && isBinaryReponse()) {
                binary = getContent();
            }
            
            return binary;
        }
        
        public String getTitle() throws IOException {
            Matcher m = TITLE_PTN.matcher(getHtml());
            if (m.find()) {
                return m.group(1);
            }
            return "";
        }
        
    }
    
    public static class Refcard implements Comparable<Refcard>, Serializable {
        
        private static final long serialVersionUID = -6637994297649433491L;

        protected int number;
        
        protected String title;
        
        protected int id;
        
        protected String filename;
        
        public Refcard() {
        }
        
        public Refcard(int number) {
            this.number = number;
        }
        
        private static String null2empty(String string) {
            return string == null ? "" : string;
        }
        
        private static String empty2null(String string) {
            return (string == null || string.length() == 0) ? null : string;
        }
        
        public static Refcard fromCsvLine(String line) {
            StringTokenizer tokenizer = new StringTokenizer(line, "\t");
            int idx = 0;
            Refcard refcard = new Refcard();
            while (tokenizer.hasMoreTokens()) {
                String token = tokenizer.nextToken();
                switch (idx) {
                case 0:
                    refcard.number = Integer.parseInt(token);
                    break;
                case 1:
                    refcard.title = token;
                    break;
                case 2:
                    refcard.id = Integer.parseInt(token);
                    break;
                case 3:
                    refcard.filename = empty2null(token);
                    break;
                }
                idx++;
            }
            
            return refcard;
        }
        
        public String toCsvLine() {
            return String.join("\t", 
                    Integer.toString(number), 
                    null2empty(title), 
                    Integer.toString(id), 
                    null2empty(filename));
        }
        
        @Override
        public String toString() {
            return String.format("no:%d id:%d filename:[%s] title:[%s]", 
                    number, id, filename, title);
        }
        
        public int compareTo(Refcard o) {
            return number - o.number;
        }
    }
    
    private void log(Object msg) {
        System.out.println(msg);
    }
    
    private void main() throws IOException, ClassNotFoundException {
        File mapfile = new File(CACHE_FILE);
        try {
            refcards = new ArrayList<>();
            refcardMap = new HashMap<>();

            if (mapfile.exists()) {
                log(String.format("Loading refcards information from file [%s]", CACHE_FILE));
                try (BufferedReader reader = new BufferedReader(
                        new FileReader(mapfile))) { 
                    String line;
                    Refcard refcard;
                    while ((line = reader.readLine()) != null) {
                        refcard = Refcard.fromCsvLine(line);
                        refcards.add(refcard);
                        refcardMap.put(refcard.id, refcard);
                    }
                }
                log(String.format("%d refcards information loaded!", refcards.size()));
            }
                        
            Collections.sort(refcards);
            
            go();
        } finally {
            if (newRefcards.size() > 0) {
                refcards.addAll(newRefcards);
                Collections.sort(refcards);
            }
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(mapfile))) {
                for (Refcard refcard : refcards) {
                    writer.write(refcard.toCsvLine());
                    writer.newLine();
                }
            } finally {
                if (logined) {
                    Action.setFollowRedirect(true);
                    try (Action action = new Action("/logout")) {
                        action.get();
                        log(action.getRc());
                        log(action.getTitle());
                    }
                }
            }
        }
    }
    
    private File getDestFile(String fn) {
        return new File(new File(PDF_OUTPUT_FD), fn);
    }
    
    private void go() throws IOException, ClassNotFoundException {
        if (useProxy) {
            Action.setProxy(new Proxy(Proxy.Type.HTTP, 
                    new InetSocketAddress(PROXY, PROXY_PORT)));
        }
        
        CookieManager manager = new CookieManager();
        manager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        CookieHandler.setDefault(manager);
        
        String html;
                
        log("Goto refcards list page...");
        
        try (Action action = new Action("/")) {
            action.get();
            log(action.getRc());
            log(action.getTitle());
            html = action.getHtml();
        }
        
        log("Now in the list page.");
        
        log("Checking if there are new refcards...");
        
        try (BufferedReader br = new BufferedReader(new StringReader(html))) {
            String line;
            while ((line = br.readLine()) != null) {
                int start = line.indexOf("<script type='text/javascript'>refcardzNodes = new Array()");
                if (start != -1) {
                    //log(String.format("got the line. the line's length = %d", line.length()));
                    
                    Matcher nm = NUMBER_REGEX.matcher(line);
                    Matcher tm = TITLE_REGEX.matcher(line);
                    Matcher im = ID_REGEX.matcher(line);
                    Refcard refcard;
                    while (nm.find()) {
                        //log("find number = " + nm.group(1));
                        if (tm.find()) {
                            //log("find name = " + tm.group(1));
                            if (im.find()) {
                                int id = Integer.parseInt(im.group(1));
                                //log("find id = " + im.group(1));
                                if (!refcardMap.containsKey(id)) {
                                    refcard = new Refcard();
                                    
                                    refcard.number = Integer.parseInt(nm.group(1));
                                    refcard.title = tm.group(1);
                                    refcard.id = id;
                                    
                                    refcardMap.put(id, refcard);
                                    newRefcards.add(refcard);
                                    
                                    refcard = null;
                                }
                            }
                        }
                    }
                }
            }
        }
        
        log(String.format("Found %d refcards.", newRefcards.size()));
        
        
        log(String.format("Checking refcards existence..."));
        
        List<Refcard> refcardsNeedDownload = new ArrayList<>();
        
        for (Refcard refcard : refcards) {
            String fn = refcard.filename;
            File pdffile;
            if (fn == null) {
                refcardsNeedDownload.add(refcard);
                continue;
            }
                
            pdffile = getDestFile(fn);
            if (!pdffile.exists() || pdffile.length() == 0) {
                refcardsNeedDownload.add(refcard);
            }
        }
        
        int cntnotexist = refcardsNeedDownload.size();
        if (cntnotexist > 0) {
            log(String.format("Found %d refcards does not exist.", cntnotexist));
        } else {
            log("No refcards found not exist.");
        }
        
        int cntnew = newRefcards.size();
        
        if (cntnew + cntnotexist == 0) {
            log("No need to download.");
        } else {
            log(String.format("%d refcards(new:%d not exist: %d) need to be download.",
                    cntnotexist + cntnew, 
                    cntnew, cntnotexist));
            
            refcardsNeedDownload.addAll(newRefcards);
            Collections.sort(refcardsNeedDownload);
            
            log("Try to login...");
            
            try (Action action = new Action("/user")) {
                action.get();
                log(action.getRc());
                log(action.getTitle());
                html = action.getHtml();
            }
            
            Pattern ptn = Pattern.compile("name=\"form_build_id\" id=\"([\\w-]+)\"");
            Matcher m = ptn.matcher(html);
            
            String formid = null;
            if (m.find()) {
                formid = m.group(1);
            }
            if (formid == null) {
                log("Can't find out formid");
                return;
            }
            
            try (Action action = new Action("/user")) {
                action.post("form_id", "user_login", 
                    "name", "reluctant1981@gmail.com",
                    "pass", "lwg#123", 
                    "form_build_id", formid);
                log(action.getRc());
                log(action.getTitle());
                log("Login OK!");
            }
            
            logined = true;

        
            Action.setFollowRedirect(false);
            
            log("Download started.");
            
            for (Refcard refcard : refcardsNeedDownload) {
                int id = refcard.id;
                String fn = refcard.filename;
                File pdffile;
                
                log(String.format("Downloading [%d] [%s]", refcard.number, 
                        refcard.title));

                if (fn == null) {
                    String pdfurl = String.format(DOWNLOAD_LINK, id);
                    try (Action action = new Action(pdfurl)) {
                        action.get();
                        int rc = action.getRc();
                        if (rc == 200) {
                            log(action.getTitle());
                        }

                        if (rc != 302) {
                            throw new RuntimeException(String.format(
                                    "Unexpected rc:%d", rc));
                        } else {
                            pdfurl = URLDecoder.decode(
                                    action.getHeader("Location"), UTF8);
                            fn = pdfurl.substring(pdfurl.lastIndexOf('/') + 1);
                            refcard.filename = fn;
                        }
                    }
                }
                
                pdffile = getDestFile(fn);
                if (pdffile.exists() && pdffile.length() > 0) {
                    log(String.format("[%s] skip", fn));
                } else {
                    String pdfurl = String.format(DIRECT_DOWNLOAD_LINK, fn);
                    try (Action a = new Action(pdfurl)) {
                        try (OutputStream fo = new FileOutputStream(
                                pdffile)) {
                            byte[] pdfdata = a.getBinary();
                            if (pdfdata == null) {
                                log(a.getTitle());
                                try (Writer w = new FileWriter(
                                        fn.replace(".pdf", ".htm"))) {
                                    w.write(a.getHtml());
                                }
                            } else {
                                fo.write(a.getBinary());
                            }
                        }
                    }
                    log(String.format("[%s] done", fn));
                }

                log(String.format("Download done [%d] [%s].", refcard.number, 
                        refcard.title));
            }
            
            log("Download done.");
            
        }
    }
    
    public static void main(String[] args) {
        try {
            new DZoneRefcardDownloader().main();
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

}