package com.scaleunlimited.flinkcrawler.functions;

import java.util.Arrays;
import java.util.Comparator;

import org.apache.flink.api.common.functions.RuntimeContext;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.scaleunlimited.flinkcrawler.metrics.CrawlerAccumulator;
import com.scaleunlimited.flinkcrawler.parser.BasePageParser;
import com.scaleunlimited.flinkcrawler.parser.ParserResult;
import com.scaleunlimited.flinkcrawler.pojos.ExtractedUrl;
import com.scaleunlimited.flinkcrawler.pojos.FetchedUrl;
import com.scaleunlimited.flinkcrawler.pojos.ParsedUrl;

@SuppressWarnings("serial")
public class ParseFunction
        extends BaseFlatMapFunction<FetchedUrl, Tuple3<ExtractedUrl, ParsedUrl, String>> {
    private static final Logger LOGGER = LoggerFactory.getLogger(ParseFunction.class);

    private static final String TABS_AND_RETURNS_PATTERN = "[\t\r\n]";
    private BasePageParser _parser;
    private int _maxOutlinksPerPage;

    public ParseFunction(BasePageParser parser, int maxOutlinksPerPage) {
        _parser = parser;
        _maxOutlinksPerPage = maxOutlinksPerPage;
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        RuntimeContext context = getRuntimeContext();
        _parser.open(new CrawlerAccumulator(context));
    }

    @Override
    public void flatMap(FetchedUrl fetchedUrl,
            Collector<Tuple3<ExtractedUrl, ParsedUrl, String>> collector) throws Exception {
        record(this.getClass(), fetchedUrl);

        ParserResult result;
        try {
            long start = System.currentTimeMillis();
            result = _parser.parse(fetchedUrl);
            LOGGER.debug("Parsed '{}' in {}ms", fetchedUrl, System.currentTimeMillis() - start);
        } catch (InterruptedException e) {
            // Ignore, as these happen when we're parsing a file and the workflow
            // is shutting down.
            LOGGER.debug("Interrupted while parsing '{}'", fetchedUrl);
            return;
        } catch (Exception e) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.warn("Parsing exception " + fetchedUrl, e);
            } else {
                // If we're not doing debug level logging, don't spit out stack trace.
                LOGGER.warn("Parsing exception '{}': {}", fetchedUrl, e.getCause().getMessage());
            }

            return;
        }

        // Output the content only if we have a score that is greater than 0
        if (result.getParsedUrl().getScore() > 0) {
            collector.collect(
                    new Tuple3<ExtractedUrl, ParsedUrl, String>(null, result.getParsedUrl(), null));
        } else {
            LOGGER.debug("Skipping content output of zero-score '{}'", fetchedUrl);
        }
        
        // TODO VMa - why are we extracting outlinks and outputting text content if the score is less than
        // or equal to zero ?

        // Since we are limiting the number of outlinks, first sort by score and then limit.
        ExtractedUrl[] extractedUrls = result.getExtractedUrls();
        Arrays.sort(extractedUrls, new Comparator<ExtractedUrl>() {

            @Override
            public int compare(ExtractedUrl o1, ExtractedUrl o2) {
                return (int) (o1.getScore() - o2.getScore());
            }
        });
        
        int count = 0;
        for (ExtractedUrl outlink : extractedUrls) {
            String message = String.format("Extracted '%s' from '%s'", outlink.getUrl(),
                    fetchedUrl.getUrl());
            LOGGER.trace(message);
            collector.collect(new Tuple3<ExtractedUrl, ParsedUrl, String>(outlink, null, null));
            count++;
            if (count >= _maxOutlinksPerPage) {
                break;
            }
        }

        // Output the text version of the content
        String contentText = makeContentText(result);
        collector.collect(new Tuple3<ExtractedUrl, ParsedUrl, String>(null, null, contentText));
    }

    private String makeContentText(ParserResult result) {
        String parsedText = result.getParsedUrl().getParsedText();
        String contentField = parsedText.replaceAll(TABS_AND_RETURNS_PATTERN, " ");
        return result.getParsedUrl().getUrl() + "\t" + contentField;
    }

}
