package coop.jamgo.zap404;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.OptionHandlerFilter;
import org.kohsuke.args4j.spi.OptionHandler;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.webmasters.Webmasters;
import com.google.api.services.webmasters.model.UrlCrawlErrorsSample;
import com.google.api.services.webmasters.model.UrlSampleDetails;

public class App {
	@Option(name="-u", usage="Target URL", required=true)
	private String url = "";
	
	@Option(name="-e", usage="Google Service Account Email", required=true)
	private String serviceAccountEmail = "";
	
	@Option(name="-f", usage="Private Key P12 File", required=true)
	private String p12File = "";
	
	@Argument
    private List<String> arguments = new ArrayList<String>();
	
	private String OAUTH_SCOPE = "https://www.googleapis.com/auth/webmasters";
	private HashMap<String, String> sitemaps = new HashMap<String, String>();

	public static void main(String[] args) throws Exception {
		(new App()).run(args);
	}

	private void run(String[] args) throws Exception {
		CmdLineParser parser = new CmdLineParser(this);

        try {
            parser.parseArgument(args);
        } catch( CmdLineException e ) {
            System.err.println(e.getMessage());
            System.err.println("java -jar zap404.jar [options...] arguments...");
            parser.printUsage(System.err);
            System.err.println();
            return;
        }
        System.out.println("OK");
        System.out.println("Example: " + parser.printExample(OptionHandlerFilter.ALL));
        System.out.println("url: " + this.url);
        System.out.println("service account email: " + this.serviceAccountEmail);
        System.out.println("p12 file: " + this.p12File);

		HttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
		JsonFactory jsonFactory = JacksonFactory.getDefaultInstance();
		
		// Build service account credential.
		GoogleCredential credential = new GoogleCredential.Builder().setTransport(httpTransport)
		    .setJsonFactory(jsonFactory)
		    .setServiceAccountId(this.serviceAccountEmail)
		    .setServiceAccountScopes(Collections.singleton(OAUTH_SCOPE))
		    .setServiceAccountPrivateKeyFromP12File(new File(p12File))
		    .build();
		
		// Create a new authorized API client
		Webmasters service = new Webmasters.Builder(httpTransport, jsonFactory, credential).setApplicationName("zap404").build();

		System.out.println("Listing crawl errors");
		List<UrlCrawlErrorsSample> crawlErrors = service.urlcrawlerrorssamples().list(this.url, "notFound", "web").execute().getUrlCrawlErrorSample();
		System.out.println("Number of errors: " + crawlErrors.size());
		int i = 0;
		for (UrlCrawlErrorsSample crawlError : crawlErrors) {
			i++;
			// if (i > 1) return;
			String pageUrl = crawlError.getPageUrl();
			System.out.println(i + ": " + pageUrl + ", last crawled: " + crawlError.getLastCrawled() + ", first detected: " + crawlError.getFirstDetected());

			if (still404(this.url + "/" + pageUrl)) {
				System.out.println("Still 404");
				UrlSampleDetails crawlErrorDetails = null;
				try {
					crawlErrorDetails = service.urlcrawlerrorssamples().get(this.url, pageUrl, "notFound", "web").execute().getUrlDetails();
				} catch (SocketTimeoutException ste) {
					System.out.println("Timeout getting crawl error details: " + ste.getMessage());
					continue;
				} catch (GoogleJsonResponseException gjre) {
					System.out.println("Google problem getting crawl error details: " + gjre.getMessage());
					continue;
				}

				boolean isLinked = false;
				
				if (crawlErrorDetails != null) {
					List<String> containingSitemaps = crawlErrorDetails.getContainingSitemaps();
					if (containingSitemaps != null) {
						System.out.println("\tNumber of linked sitemaps: " + containingSitemaps.size());
						for (String linkedSitemap : containingSitemaps) {
							if (stillInSitemap(linkedSitemap, pageUrl)) {
								isLinked = true;
								System.out.println("Linked from sitemap: " + linkedSitemap);
								break;
							}
						}
					}
					if (!isLinked) {
						List<String> linkedFromUrls = crawlErrorDetails.getLinkedFromUrls();
						if (linkedFromUrls != null) {
							System.out.println("\tNumber of linked pages: " + linkedFromUrls.size());
							for (String linkedUrl : linkedFromUrls) {
								if (stillInUrl(linkedUrl, pageUrl)) {
									isLinked = true;
									System.out.println("Linked from page: " + linkedUrl);
									break;
								}
							}
						}
					}
				} else {
					System.out.println("Crawl error details are empty");
				}

				if (!isLinked) {
					System.out.println("Not linked. Going to mark as fixed");
					markAsFixed(service, crawlError.getPageUrl());
				}
			} else {
				System.out.println("Not 404. Going to mark as fixed");
				markAsFixed(service, pageUrl);
			}
		}

	}

	private void markAsFixed(Webmasters service, String pageUrl) {
		try {
			service.urlcrawlerrorssamples().markAsFixed(this.url, pageUrl, "notFound", "web").execute();
			System.out.println("Marked as fixed");
		} catch (Exception e) {
			System.out.println("Error: " + e.getMessage());
		}
	}

	private boolean still404(String url) throws Exception {
		URL u = new URL(url);
		HttpURLConnection conn = (HttpURLConnection) u.openConnection();
		conn.setRequestMethod("GET");
		conn.connect();
		return (conn.getResponseCode() == 404);
	}

	private boolean stillInSitemap(String url, String pageUrl) throws Exception {
		System.out.println("\t\tChecking " + url);
		String sitemap = sitemaps.get(url);
		if (sitemap == null) {
			System.out.println("\t\tReading & storing sitemap: " + url);
			try {
				sitemap = readUrl(url);	
			} catch (FileNotFoundException exc) {
				System.out.println("\t\tSitemap not found: " + url);
				return false;
			}
			sitemaps.put(url, sitemap);
		}
		return (sitemap.contains(pageUrl));
	}

	private String readUrl(String url) throws Exception {
		URL u = new URL(url);
		URLConnection connection = u.openConnection();
		BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
		StringBuilder response = new StringBuilder();
		String inputLine;
		while ((inputLine = in.readLine()) != null)
			response.append(inputLine);

		in.close();
		return response.toString();
	}

	private boolean stillInUrl(String url, String pageUrl) throws Exception {
		URL u = new URL(url);
		System.out.println("\t\tChecking " + url);

		boolean found = false;
		try {
			URLConnection conn = u.openConnection();
			conn.setConnectTimeout(30000);
			conn.setReadTimeout(120000);
			BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
			String inputLine;
			while ((inputLine = in.readLine()) != null) {
				if (inputLine.contains(pageUrl)) {
					found = true;
					break;
				}
			}
			in.close();
		} catch (FileNotFoundException e) {
			System.out.println("\t\t404");
			return false;
		} catch (IOException ioe) {
			System.out.println("\t\tError: " + ioe.getMessage());
			return false;
		}
		if (!found)
			System.out.println("\t\tLink Not Found");
		return found;
	}
}
