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
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;

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
 * @phase package
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

    @Override
    public void execute() throws MojoExecutionException {

        if (baseURL != null && outputDirectory != null) {
            getLog().debug("starting build URL from " + baseURL);
            if (!baseURL.endsWith(File.separator)) {
                baseURL = baseURL + File.separator;
            }
            outputDirectory.mkdirs();

            String encoding = new String(Base64.encodeBase64(credential.getBytes()));

            for (int i = 0; i < modules.length; i++) {
                URL url = null;
                try {
                    url = new URL(baseURL + modules[i] + "/?details");
                } catch (MalformedURLException e) {
                    getLog().error("Malformed URL: " + baseURL + modules[i] + "/?details");
                }
                if (url != null) {
                    JSONParser parser = new JSONParser();
                    try {
                        URLConnection uc = url.openConnection();
                        uc.setRequestProperty("Authorization", "Basic " + encoding);

                        JSONObject json =
                            (JSONObject) parser.parse(new BufferedReader(new InputStreamReader(uc.getInputStream(),
                                Charset.forName("UTF-8"))));
                        JSONArray lgs = (JSONArray) json.get("available_languages");
                        for (Object obj : lgs) {
                            if (obj instanceof JSONObject) {
                                Object code = ((JSONObject) obj).get("code");
                                if (code != null && !"en_US".equals(code)) {
                                    try {
                                        URL ts =
                                            new URL(baseURL + modules[i] + "/translation/" + code.toString() + "/?file");
                                        URLConnection tsc = ts.openConnection();
                                        tsc.setRequestProperty("Authorization", "Basic " + encoding);
                                        writeFile(tsc.getInputStream(), new FileOutputStream(new File(outputDirectory,
                                            "messages_" + code.toString() + ".properties")));
                                    } catch (MalformedURLException e) {
                                        getLog().error(
                                            baseURL + modules[i] + "/translation/" + code.toString() + "/?file");
                                    }
                                }

                            }
                        }
                    } catch (ParseException pe) {
                        getLog().error("JSON parsing error, position: " + pe.getPosition());
                        getLog().error(pe);
                    } catch (IOException e) {
                        getLog().error(e);
                    }
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
            while ((str = br.readLine()) != null) {
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
            br.close();
            bw.close();
            return -1;
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
}
