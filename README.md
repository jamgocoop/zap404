# zap404
**Automate crawl error clean up in Google Webmaster Tools**

Once built, the program can be invoked with:
`java -jar zap404.jar -u <website-url> -e <google-service-account-email> -f <private-key-file>`

This program will use the Google Webmaster Tools API and clean up crawl errors for a given website:
*  Inspect the crawl errors report (URLs indexed by Google that are giving a 404 error)
*  For each error URL, check if it still gives a 404, otherwise mark as fixed
*  If it still gives 404, check where it is referenced from (a sitemap, another URL, internal or external)
*  If all references do not contain the URL anymore, mark as fixed
*  Output a log with all actions taken

This process can be useful after a site redesign, when URLs have changed or disappeared and Webmaster Tools reports lots of 404 errors. When the number of errors is large (far more than the 1000 daily limit reported by Webmaster Tools), it can be very convenient to automate the process.

After the script runs periodically once a day for several days, the number of crawl errors stabilizes, and all errors left are the ones that have to be fixed manually by defining redirections or asking origin webmasters to change the referenced URL.

See our [blog entry](http://blog.jamgo.coop/2015/08/06/clean-up-crawl…ebmaster-tools/) for details on the code and motivation for the program.

## Prerrequisites
Ths program uses the Google Webmaster Tools API and the args4j library. Both are declared in the pom.xml file, so
if you build with Maven you're all set. Just mvn clean install to generate a jar with all the dependencies.

Otherwise you can obtain the libraries from [Google](https://developers.google.com/webmaster-tools/v3/quickstart/quickstart-java) and [Kohsuke](http://args4j.kohsuke.org/)

Setting up a Google Service Account with permissions to access the Webmaster Tools data for the website is explained in "Step 1: Enable the Webmaster Tools API" at the following [link](https://developers.google.com/webmaster-tools/v3/quickstart/quickstart-java)

