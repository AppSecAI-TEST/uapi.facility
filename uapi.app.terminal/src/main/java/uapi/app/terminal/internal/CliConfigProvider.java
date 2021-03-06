/*
 * Copyright (C) 2017. The UAPI Authors
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at the LICENSE file.
 *
 * You must gained the permission from the authors if you want to
 * use the project into a commercial product
 */

package uapi.app.terminal.internal;

import com.google.common.base.Strings;
import uapi.Tags;
import uapi.common.ArgumentChecker;
import uapi.common.Pair;
import uapi.config.IConfigProvider;
import uapi.config.IConfigTracer;
import uapi.log.ILogger;
import uapi.rx.Looper;
import uapi.service.annotation.Inject;
import uapi.service.annotation.Service;
import uapi.service.annotation.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * The command line parser, it should support below pattern:
 * "-x" : x option with a boolean value which always set to true
 * "-x=???" : x option with value
 * "-xtrf" : x, t, r, f option which boolean value which always set to true
 */
@Service
@Tag(Tags.CONFIG)
public class CliConfigProvider implements IConfigProvider {

    private static final String DEFAULT_OPTION_PREFIX           = "-";
    private static final String DEFAULT_OPTION_VALUE_SEPARATOR  = "=";

    @Inject
    protected ILogger _logger;

    @Inject
    protected IConfigTracer _configTracer;

    private String _optionPrefix = DEFAULT_OPTION_PREFIX;
    private String _optionValueSeparator = DEFAULT_OPTION_VALUE_SEPARATOR;

    public void setOptionPrefix(String prefix) {
        ArgumentChecker.notEmpty(prefix, "prefix");
        this._optionPrefix = prefix;
    }

    public void setOptionValueSeparator(String separator) {
        this._optionValueSeparator = separator;
    }

    public void parse(String[] args) {
        if (args == null || args.length == 0) {
            return;
        }
        Looper.on(args)
                .filter(option -> ! Strings.isNullOrEmpty(option))
                .filter(option -> {
                    if (option.startsWith(this._optionPrefix)) {
                        return true;
                    } else {
                        this._logger.warn("The command line option is invalid - {}", option);
                        return false;
                    }
                })
                .map(option -> option.substring(this._optionPrefix.length()))
                .map(option -> Pair.splitTo(option, this._optionValueSeparator))
                .flatmap(pair -> {
                    if (Strings.isNullOrEmpty(pair.getRightValue())) {
                        List<Pair<String, String>> pairs = new ArrayList<>();
                        for (char c : pair.getLeftValue().toCharArray()) {
                            pairs.add(new Pair<>(String.valueOf(c), Boolean.TRUE.toString()));
                        }
                        return Looper.on(pairs);
                    } else {
                        return Looper.on(pair);
                    }
                })
                .foreach(
                        pair -> this._configTracer.onChange(
                                QUALIFY_SYSTEM + pair.getLeftValue(), pair.getRightValue()));
    }
}
