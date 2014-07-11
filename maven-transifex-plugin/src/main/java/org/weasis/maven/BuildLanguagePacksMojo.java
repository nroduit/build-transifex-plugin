package org.weasis.maven;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.Authenticator;
import java.net.MalformedURLException;
import java.net.PasswordAuthentication;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.util.Properties;
import java.util.Scanner;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.codehaus.plexus.util.Base64;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

/**
 * Goal capable of downloading translation files for building weasis-i18n.
 * 
 * @goal buildLanguagePacks
 * 
 * @phase process-resources
 */
public class BuildLanguagePacksMojo extends AbstractMojo {

    /**
     * Base URL of the transifex project
     * 
     * @parameter expression="${transifex.baseURL}"
     * @required
     */
    private String baseURL;

    /**
     * Credential (username:password) to connect on the transifex WEB API.
     * 
     * @parameter expression="${transifex.credential}"
     * @required
     */
    private String credential;

    /**
     * The directory where files are written.
     * 
     * @parameter expression="${transifex.outputDirectory}"
     * @required
     */
    private File outputDirectory;

    /**
     * List of transifex resources
     * 
     * @parameter expression="${transifex.modules}"
     * @required
     */
    private String[] modules;

    /**
     * The directory where the list of languages are written.
     * 
     * @parameter expression="${transifex.buildLanguagesFile}"
     */
    private File buildLanguagesFile;

    @Override
    public void execute() throws MojoExecutionException {

        if (baseURL != null && outputDirectory != null) {
            getLog().debug("starting build URL from " + baseURL);
            if (!baseURL.endsWith("/")) {
                baseURL = baseURL + "/";
            }
            outputDirectory.mkdirs();

            setProxyAuthentication();
            String encoding = new String(Base64.encodeBase64(credential.getBytes()));

            for (int i = 0; i < modules.length; i++) {
                StringBuilder lgList = new StringBuilder("en");
                boolean writeAvailableLanguages = buildLanguagesFile != null;
                URL url = null;
                try {
                    url = new URL(baseURL + modules[i] + "/?details");
                    getLog().debug(modules[i] + " URL: " + url.toString());
                } catch (MalformedURLException e) {
                    getLog().error("Malformed URL: " + baseURL + modules[i] + "/?details");
                }
                if (url != null) {
                    JSONParser parser = new JSONParser();
                    try {
                        URLConnection uc = url.openConnection();
                        uc.setRequestProperty("Authorization", "Basic " + encoding);
                        // Set Mozilla agent otherwise return an error: Server returned HTTP response code: 403 for URL
                        uc.addRequestProperty("User-Agent", "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.0)");
                        JSONObject json =
                            (JSONObject) parser.parse(new BufferedReader(new InputStreamReader(uc.getInputStream(),
                                Charset.forName("UTF-8"))));
                        JSONArray lgs = (JSONArray) json.get("available_languages");
                        for (Object obj : lgs) {
                            if (obj instanceof JSONObject) {
                                Object code = ((JSONObject) obj).get("code");
                                if (code != null && !"en".equals(code)) {
                                    try {
                                        URL ts =
                                            new URL(baseURL + modules[i] + "/translation/" + code.toString() + "/?file");
                                        URLConnection tsc = ts.openConnection();
                                        tsc.setRequestProperty("Authorization", "Basic " + encoding);
                                        // Set Mozilla agent otherwise return an error: Server returned HTTP response
                                        // code: 403 for URL
                                        tsc.addRequestProperty("User-Agent",
                                            "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.0)");
                                        getLog().debug("Language URL: " + ts.toString());
                                        File outFile =
                                            new File(outputDirectory, "messages_" + code.toString() + ".properties");
                                        if (writeFile(tsc.getInputStream(), new FileOutputStream(outFile)) == 0) {
                                            // Do not write file with no translation
                                            outFile.delete();
                                        } else if (writeAvailableLanguages) {
                                            lgList.append("," + code);
                                        }
                                    } catch (MalformedURLException e) {
                                        getLog().error(
                                            baseURL + modules[i] + "/translation/" + code.toString() + "/?file");
                                    }
                                }
                            }
                        }
                        if (writeAvailableLanguages) {
                            Properties p = new Properties();
                            p.setProperty("languages", lgList.toString());
                            p.store(new FileOutputStream(buildLanguagesFile), null);
                        }
                    } catch (ParseException pe) {
                        getLog().error("JSON parsing error, position: " + pe.getPosition());
                        getLog().error(pe);
                        throw new MojoExecutionException("Cannot parse source file!");
                    } catch (IOException e) {
                        getLog().error(e);
                        throw new MojoExecutionException("Cannot download source files!");
                    }
                }
            }
        }

    }

    private static void setProxyAuthentication() {
        String proxy = System.getProperty("http.proxyHost");
        if (proxy != null) {
            URL url;
            try {
                url = new URL("http://www.google.com");
                URLConnection con = url.openConnection();
                con.getInputStream();
            } catch (Exception e) {
                String message = e.getMessage();
                if (message != null && message.contains("407")) {
                    String userName = System.getProperty("http.proxyUser", System.getProperty("https.proxyUser"));
                    if (userName == null) {
                        System.out.println("Enter the proxy username: ");
                        userName = new Scanner(System.in).nextLine();
                    }

                    String userPassword =
                        System.getProperty("http.proxyPassword", System.getProperty("https.proxyPassword"));
                    if (userPassword == null) {
                        System.out.println("Enter the proxy password: ");
                        userPassword = new Scanner(System.in).nextLine();
                    }

                    final String authUser = userName;
                    final String authPassword = userPassword;

                    Authenticator.setDefault(new Authenticator() {
                        @Override
                        public PasswordAuthentication getPasswordAuthentication() {
                            return new PasswordAuthentication(authUser, authPassword.toCharArray());
                        }
                    });
                } else {
                    e.printStackTrace();
                }
            }

        }
    }

    /**
     * @param inputStream
     * @param out
     * @return bytes transferred. O = error, -1 = all bytes has been transferred, other = bytes transferred before
     *         interruption
     */
    public static int writeFile(InputStream inputStream, OutputStream out) {
        if (inputStream == null || out == null) {
            return 0;
        }

        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, "ISO-8859-1"));
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(out, "ISO-8859-1"));
            String str;
            boolean hasTranslations = false;
            while ((str = br.readLine()) != null) {
                if (hasText(str) && !str.startsWith("#")) {
                    hasTranslations = true;
                    int x = 0;
                    int y = 0;
                    StringBuilder result = new StringBuilder();
                    while ((x = str.indexOf("\\\\", y)) > -1) {
                        result.append(str, y, x);
                        result.append('\\');
                        y = x + 2;
                    }
                    result.append(str, y, str.length());
                    result.append("\n");
                    bw.write(result.toString());
                }
            }
            br.close();
            bw.close();
            return hasTranslations ? -1 : 0;
        } catch (IOException e) {
            e.printStackTrace();
            return 0;
        } finally {
            safeClose(inputStream);
            safeClose(out);
        }
    }

    public static void safeClose(final Closeable object) {
        try {
            if (object != null) {
                object.close();
            }
        } catch (IOException e) {
            // Do nothing
        }
    }

    public static boolean hasLength(CharSequence str) {
        return (str != null && str.length() > 0);
    }

    public static boolean hasLength(String str) {
        return hasLength((CharSequence) str);
    }

    public static boolean hasText(CharSequence str) {
        if (!hasLength(str)) {
            return false;
        }
        int strLen = str.length();
        for (int i = 0; i < strLen; i++) {
            if (!Character.isWhitespace(str.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasText(String str) {
        return hasText((CharSequence) str);
    }

}
