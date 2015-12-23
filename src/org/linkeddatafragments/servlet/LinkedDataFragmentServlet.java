package org.linkeddatafragments.servlet;

import com.google.gson.JsonObject;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import freemarker.template.TemplateException;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map.Entry;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFLanguages;
import org.linkeddatafragments.config.ConfigReader;
import org.linkeddatafragments.datasource.DataSourceFactory;
import org.linkeddatafragments.datasource.IDataSource;
import org.linkeddatafragments.datasource.hdt.HdtDataSourceType;
import org.linkeddatafragments.datasource.index.IndexDataSource;
import org.linkeddatafragments.datasource.tdb.JenaTDBDataSourceType;
import org.linkeddatafragments.exceptions.DataSourceException;
import org.linkeddatafragments.exceptions.DataSourceNotFoundException;
import org.linkeddatafragments.exceptions.NoRegisteredMimeTypesException;
import org.linkeddatafragments.fragments.FragmentRequestParserBase;
import org.linkeddatafragments.fragments.LinkedDataFragment;
import org.linkeddatafragments.fragments.LinkedDataFragmentRequest;
import org.linkeddatafragments.util.MIMEParse;
import org.linkeddatafragments.views.HtmlWriter;

/**
 * Servlet that responds with a Linked Data Fragment.
 *
 * @author Ruben Verborgh
 * @author Bart Hanssens
 * @author <a href="http://olafhartig.de">Olaf Hartig</a>
 */
public class LinkedDataFragmentServlet extends HttpServlet {

    private final static long serialVersionUID = 1L;

    // Parameters
    public final static String CFGFILE = "configFile";

    private ConfigReader config;
    private final HashMap<String, IDataSource> dataSources = new HashMap<>();
    private final Collection<String> mimeTypes = new ArrayList<>();

    public LinkedDataFragmentServlet() {
        HdtDataSourceType.register();
        JenaTDBDataSourceType.register();
    }

    private File getConfigFile(ServletConfig config) throws IOException {
        String path = config.getServletContext().getRealPath("/");
        if (path == null) {
            // this can happen when running standalone
            path = System.getProperty("user.dir");
        }
        File cfg = new File(path, "config-example.json");
        if (config.getInitParameter(CFGFILE) != null) {
            cfg = new File(config.getInitParameter(CFGFILE));
        }
        if (!cfg.exists()) {
            throw new IOException("Configuration file " + cfg + " not found.");
        }
        if (!cfg.isFile()) {
            throw new IOException("Configuration file " + cfg + " is not a file.");
        }
        return cfg;
    }

    @Override
    public void init(ServletConfig servletConfig) throws ServletException {
        try {
            // load the configuration
            File configFile = getConfigFile(servletConfig);
            config = new ConfigReader(new FileReader(configFile));

            for (Entry<String, JsonObject> dataSource : config.getDataSources().entrySet()) {
                dataSources.put(dataSource.getKey(), DataSourceFactory.create(dataSource.getValue()));
            }

            // register content types
            MIMEParse.register("text/html");
            MIMEParse.register(Lang.TTL.getHeaderString());
            MIMEParse.register(Lang.JSONLD.getHeaderString());
            MIMEParse.register(Lang.NTRIPLES.getHeaderString());
            MIMEParse.register(Lang.RDFXML.getHeaderString());
        } catch (IOException | DataSourceException e) {
            throw new ServletException(e);
        }
    }

    @Override
    public void destroy()
    {
        for ( IDataSource dataSource : dataSources.values() ) {
            try {
                dataSource.close();
            }
            catch( Exception e ) {
                // ignore
            }
        }   
    }

    /**
     * Get the datasource
     *
     * @param request
     * @return
     * @throws IOException
     */
    private IDataSource getDataSource(HttpServletRequest request) throws DataSourceNotFoundException {
        String contextPath = request.getContextPath();
        String requestURI = request.getRequestURI();

        String path = contextPath == null
                ? requestURI
                : requestURI.substring(contextPath.length());

        if (path.equals("/") || path.isEmpty()) {
            final String baseURL = FragmentRequestParserBase.extractBaseURL(request, config);
            return new IndexDataSource(baseURL, dataSources);
        }

        String dataSourceName = path.substring(1);
        IDataSource dataSource = dataSources.get(dataSourceName);
        if (dataSource == null) {
            throw new DataSourceNotFoundException(dataSourceName);
        }
        return dataSource;
    }

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException {
        try {
            final IDataSource dataSource = getDataSource( request );

            final LinkedDataFragmentRequest ldfRequest =
                    dataSource.getRequestParser()
                              .parseIntoFragmentRequest( request, config );

            final LinkedDataFragment fragment =
                    dataSource.getRequestProcessor()
                              .createRequestedFragment( ldfRequest );
            
            // do conneg
            String bestMatch = MIMEParse.bestMatch(request.getHeader("Accept"));

            // serialize the output
            response.setHeader("Server", "Linked Data Fragments Server");
            response.setContentType(bestMatch);
            response.setCharacterEncoding("utf-8");
            
            if (bestMatch.equals("text/html")) {
                new HtmlWriter().write(response.getOutputStream(), dataSource, fragment, ldfRequest);
                return;
            }

            final Model output = ModelFactory.createDefaultModel();
            output.setNsPrefixes(config.getPrefixes());
            output.add( fragment.getMetadata() );
            output.add( fragment.getTriples() );
            output.add( fragment.getControls() );
            
            Lang contentType = RDFLanguages.contentTypeToLang(bestMatch);
            RDFDataMgr.write(response.getOutputStream(), output, contentType);   

        } catch (IOException | NoRegisteredMimeTypesException | TemplateException e) {
            throw new ServletException(e);
        } catch (DataSourceNotFoundException ex) {
            try {
                response.setStatus(404);
                response.getOutputStream().println(ex.getMessage());
                response.getOutputStream().close();
            } catch (IOException ex1) {
                throw new ServletException(ex1);
            }
        }
    }

}
