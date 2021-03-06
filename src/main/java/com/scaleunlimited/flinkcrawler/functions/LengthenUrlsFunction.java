package com.scaleunlimited.flinkcrawler.functions;

import java.util.Collections;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.async.ResultFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.scaleunlimited.flinkcrawler.pojos.RawUrl;
import com.scaleunlimited.flinkcrawler.urls.BaseUrlLengthener;

@SuppressWarnings({
        "serial"
})
public class LengthenUrlsFunction extends BaseAsyncFunction<RawUrl, RawUrl> {
    static final Logger LOGGER = LoggerFactory.getLogger(LengthenUrlsFunction.class);

    // FUTURE make this settable from command line
    // See https://github.com/ScaleUnlimited/flink-crawler/issues/50
    private static final int THREAD_COUNT = 100;

    private BaseUrlLengthener _lengthener;

    public LengthenUrlsFunction(BaseUrlLengthener lengthener) {
        super(THREAD_COUNT, lengthener.getTimeoutInSeconds());

        _lengthener = lengthener;
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        _lengthener.open();
    }

    @Override
    public void asyncInvoke(final RawUrl url, ResultFuture<RawUrl> future) throws Exception {
        record(this.getClass(), url);

        _executor.execute(new Runnable() {

            @Override
            public void run() {
                RawUrl lengthenedUrl = _lengthener.lengthen(url);
                future.complete(Collections.singleton(lengthenedUrl));
            }
        });
    }

}
