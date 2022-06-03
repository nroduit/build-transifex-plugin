/*******************************************************************************
 * Copyright (c) 2009-2019 Nicolas Roduit and other contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.weasis.maven;

import java.io.BufferedReader;
import java.io.BufferedWriter;
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
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Optional;
import java.util.Properties;
import java.util.Scanner;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * Goal capable of downloading translation files for building weasis-i18n.
 *
 * @goal buildLanguagePacks
 * @phase process-resources
 */
public class BuildLanguagePacksMojo extends AbstractMojo {

  /**
   * Base URL of the transifex project
   *
   * @parameter property="transifex.baseURL"
   * @required
   */
  private String baseURL;

  /**
   * The organization name
   *
   * @parameter property="transifex.organization"
   * @required
   */
  private String organization;

  /**
   * The project name
   *
   * @parameter property="transifex.project"
   * @required
   */
  private String project;

  /**
   * Token to connect on the transifex WEB API. You can generate one at
   * https://www.transifex.com/user/settings/api/
   *
   * @parameter property="transifex.token"
   * @required
   */
  private String token;

  /**
   * The directory where files are written.
   *
   * @parameter property="transifex.outputDirectory"
   * @required
   */
  private File outputDirectory;

  /**
   * List of transifex resources
   *
   * @parameter property="transifex.modules"
   * @required
   */
  private String[] modules;

  /**
   * List of the final names according to the transifex resources
   *
   * @parameter property="transifex.baseNames"
   */
  private String[] baseNames;

  /**
   * The directory where the list of languages are written.
   *
   * @parameter property="transifex.buildLanguagesFile"
   */
  private File buildLanguagesFile;

  public static boolean hasLength(CharSequence str) {
    return (str != null && str.length() > 0);
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

  @Override
  public void execute() throws MojoExecutionException {

    if (baseURL != null && outputDirectory != null) {
      getLog().debug("starting build URL from " + baseURL);
      if (!baseURL.endsWith("/")) {
        baseURL = baseURL + "/";
      }
      outputDirectory.mkdirs();

      setProxyAuthentication();

      for (int i = 0; i < modules.length; i++) {

        boolean writeAvailableLanguages = buildLanguagesFile != null;
        URL projectLanguagesUrl = null;
        try {
          projectLanguagesUrl =
              new URL(
                  String.format("%sprojects/o:%s:p:%s/languages", baseURL, organization, project));
          getLog().debug("Languages URL: " + projectLanguagesUrl);
        } catch (MalformedURLException e) {
          getLog().error(e);
        }
        if (projectLanguagesUrl != null) {
          try {
            download(projectLanguagesUrl, writeAvailableLanguages, i);
          } catch (IOException e) {
            getLog().error(e);
            throw new MojoExecutionException("Cannot download source files!");
          }
        }
      }
    }
  }

  private void download(URL url, boolean writeAvailableLanguages, int i) throws IOException {
    StringBuilder lgList = new StringBuilder("en");
    URLConnection uc = url.openConnection();
    uc.setRequestProperty("Authorization", getAuthorization());
    try (BufferedReader bufReader =
        new BufferedReader(new InputStreamReader(uc.getInputStream(), StandardCharsets.UTF_8))) {
      JSONTokener tokener = new JSONTokener(bufReader);
      JSONObject object = new JSONObject(tokener);
      JSONArray lgs = object.getJSONArray("data");
      for (Object obj : lgs) {
        if (obj instanceof JSONObject) {
          Object code = ((JSONObject) obj).get("id");
          if (code != null) {
            try {
              String playLoad =
                  String.format(
                      "{\n"
                          + "  \"data\": {\n"
                          + "    \"attributes\": {\n"
                          + "      \"callback_url\": null,\n"
                          + "      \"content_encoding\": \"text\",\n"
                          + "      \"file_type\": \"default\",\n"
                          + "      \"mode\": \"default\",\n"
                          + "      \"pseudo\": false\n"
                          + "    },\n"
                          + "    \"relationships\": {\n"
                          + "      \"language\": {\n"
                          + "        \"data\": {\n"
                          + "          \"id\": \"%s\",\n"
                          + "          \"type\": \"languages\"\n"
                          + "        }\n"
                          + "      },\n"
                          + "      \"resource\": {\n"
                          + "        \"data\": {\n"
                          + "          \"id\": \"o:%s:p:%s:r:%s\",\n"
                          + "          \"type\": \"resources\"\n"
                          + "        }\n"
                          + "      }\n"
                          + "    },\n"
                          + "    \"type\": \"resource_translations_async_downloads\"\n"
                          + "  }\n"
                          + "}",
                      code, organization, project, modules[i]);
              HttpResponse<String> response =
                  postJSON(new URI(baseURL + "resource_translations_async_downloads"), playLoad);

              String finalName = "messages";
              if (baseNames != null && baseNames.length > i) {
                finalName = baseNames[i];
              }

              URL file = getDownloadURL(response.body());
              if (file != null) {
                String lg = code.toString().substring(2);
                URLConnection uc2 = file.openConnection();
                uc2.setRequestProperty("Authorization", "Bearer " + this.token);
                File outFile = new File(outputDirectory, finalName + "_" + lg + ".properties");
                if (writeFile(uc2.getInputStream(), new FileOutputStream(outFile)) == 0) {
                  // Do not write file with no translation
                  Files.delete(outFile.toPath());
                } else if (writeAvailableLanguages) {
                  lgList.append(",").append(lg);
                }
              }
            } catch (Exception e) {
              getLog().error(e);
            }
          }
        }
      }
    }
    if (writeAvailableLanguages) {
      Properties p = new Properties();
      p.setProperty("languages", lgList.toString());
      try (FileOutputStream out = new FileOutputStream(buildLanguagesFile)) {
        p.store(out, null);
      }
    }
  }

  private String getAuthorization() {
    return "Bearer " + token;
  }

  public HttpResponse<String> postJSON(URI uri, String requestBody)
      throws IOException, InterruptedException {

    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .header("Content-Type", "application/vnd.api+json")
            .header("Authorization", getAuthorization())
            .POST(BodyPublishers.ofString(requestBody))
            .build();

    return HttpClient.newHttpClient().send(request, BodyHandlers.ofString());
  }

  public HttpResponse<String> getFile(String uri)
      throws IOException, InterruptedException, URISyntaxException {

    HttpRequest request =
        HttpRequest.newBuilder(new URI(uri)).header("Authorization", getAuthorization()).build();

    return HttpClient.newHttpClient().send(request, BodyHandlers.ofString());
  }

  public URL getDownloadURL(String response) throws IOException, URISyntaxException {
    JSONObject data = new JSONObject(response).getJSONObject("data");
    String id = data.getString("id");
    String link = baseURL + "resource_translations_async_downloads/" + id;
    for (int retry = 0; retry < 20; ++retry) {
      try {
        HttpResponse<String> fileResponse = getFile(link);
        if (fileResponse.statusCode() == 303) {
          Optional<String> location = fileResponse.headers().firstValue("location");
          if (location.isPresent()) {
            return new URL(location.get());
          }
        }
        JSONObject data2 = new JSONObject(fileResponse.body()).getJSONObject("data");
        String status = data2.getJSONObject("attributes").getString("status");
        if (status.equals("pending") || status.equals("processing")) {
          Thread.sleep(500);
        } else {
          if (status.equals("failed")) {
            throw new IllegalAccessError("Cannot download the resource: " + fileResponse.body());
          }
        }
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        getLog()
            .warn(
                "Interrupt exception received so interrupting thread and breaking out of retry loop");
        break;
      }
    }
    throw new IllegalStateException("Cannot get the resource " + response);
  }

  private void setProxyAuthentication() {
    String proxy = System.getProperty("http.proxyHost");
    if (proxy != null) {
      try (InputStream in = new URL("https://www.google.com").openConnection().getInputStream()) {
        getLog().debug("Can access to https://www.google.com");
      } catch (Exception e) {
        String message = e.getMessage();
        if (message != null && message.contains("407")) {
          String userName =
              System.getProperty("http.proxyUser", System.getProperty("https.proxyUser"));
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

          Authenticator.setDefault(
              new Authenticator() {
                @Override
                public PasswordAuthentication getPasswordAuthentication() {
                  return new PasswordAuthentication(authUser, authPassword.toCharArray());
                }
              });
        } else {
          getLog().error(e);
        }
      }
    }
  }

  /**
   * @param inputStream
   * @param out
   * @return bytes transferred. O = error, -1 = all bytes has been transferred, other = bytes
   *     transferred before interruption
   */
  public int writeFile(InputStream inputStream, OutputStream out) {
    if (inputStream == null || out == null) {
      return 0;
    }

    try (BufferedReader br =
            new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.ISO_8859_1));
        BufferedWriter bw =
            new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.ISO_8859_1))) {
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
      return hasTranslations ? -1 : 0;
    } catch (IOException e) {
      getLog().error(e);
      return 0;
    }
  }
}
