/*global process, require */

(function() {
    'use strict';

    const _ = require('lodash');
    const webpack = require('webpack');
    const webpackConfig = _.merge(require(process.argv[2]), JSON.parse(process.argv[3]), {watch: false});

    webpack(webpackConfig, function(err, stats) {
        if (err) throw err;
        const outputOptions = {
            colors: {level: 2, hasBasic: true, has256: true, has16m: false},
            cached: false,
            cachedAssets: false,
            modules: true,
            chunks: false,
            reasons: false,
            errorDetails: false,
            chunkOrigins: false,
            exclude: ['node_modules', 'bower_components', 'jam', 'components']
        };
        const hasErrors = stats.hasErrors();
        console[hasErrors ? 'error' : 'log'](stats.toString(outputOptions) + '\n');
        console.log('\u0010' + !hasErrors);
    });
})();
