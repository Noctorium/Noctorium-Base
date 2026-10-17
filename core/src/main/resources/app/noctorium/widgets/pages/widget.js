/*
 * What every Noctorium stream widget does, whatever it looks like. Written into each look's page where it says so,
 * by the server, so the page a streaming program loads is one file with nothing to fetch from anywhere else.
 *
 * It reads /api/now-playing about once a second and keeps the page in step with it:
 *
 *   - the page is drawn at the size its <html> names (data-width, data-height) and scaled to whatever size the
 *     browser source is, kept in the middle -- or, with data-fit="width", as tall as the source and as wide;
 *   - [data-text] elements are given the song's title, artist, album, service, "Artist - Title" line, status and
 *     times; [data-mark] svgs the service's mark; img[data-cover] the cover, which is fetched once per song from
 *     this server as a blob, so that the page can also read its colours;
 *   - [data-progress] elements are scaled along by how far the song is, moved on smoothly between readings;
 *   - classes on <html> say the rest, for each look's own style to act on: is-playing, is-paused, is-loading,
 *     is-hidden (nothing playing, Noctorium gone, or paused for longer than asked), is-leaving and is-arriving
 *     (a change of song, leaving then arriving), no-cover, no-progress, no-logo, no-art, no-album, no-duration.
 *
 * A look can do more in window.widget: track(track, cover) when a song arrives, progress(fraction, positionMs,
 * durationMs) on every frame, and resize() once the page has been fitted.
 *
 * Options come from Settings, in the reply, unless the page's own address says otherwise: ?cover=0, ?progress=0,
 * ?logo=0, ?hidePaused=0, ?hideAfter=10, ?accent=ff3366, ?align=left (or right, top, bottom, top-left and so on).
 */
(function () {
    'use strict';

    var POLL_MS = 1000;
    var RETRY_MS = 2000;
    // How many readings may fail before the widget takes itself away: Noctorium closed, or the widgets switched off.
    var FAILURES_BEFORE_HIDING = 3;
    var COVER_WAIT_MS = 2500;

    var root = document.documentElement;
    var stage = document.getElementById('stage');
    var designWidth = Number(root.getAttribute('data-width')) || 640;
    var designHeight = Number(root.getAttribute('data-height')) || 160;
    var fit = root.getAttribute('data-fit') || 'contain';
    var leaveMs = Number(root.getAttribute('data-leave')) || 320;
    var arriveMs = Number(root.getAttribute('data-arrive')) || 650;
    var hooks = window.widget || {};
    var query = new URLSearchParams(location.search);

    // --- What the address asks for, before what Settings says ---

    function given(name) {
        return query.has(name) ? query.get(name) : null;
    }

    function flag(name, fallback) {
        var value = given(name);
        if (value === null || value === '') return fallback;
        return !/^(0|false|no|off|hide)$/i.test(value);
    }

    function number(name, fallback) {
        var value = given(name);
        var parsed = value === null ? NaN : Number(value);
        return isFinite(parsed) && parsed >= 0 ? parsed : fallback;
    }

    function colour(name) {
        var value = (given(name) || '').replace(/^#/, '');
        return /^[0-9a-f]{6}$/i.test(value) ? '#' + value.toLowerCase() : null;
    }

    // --- Fitting the page to the browser source ---

    var align = (given('align') || '').toLowerCase();
    var fittedTo = '';

    function fitStage() {
        var width = window.innerWidth || designWidth;
        var height = window.innerHeight || designHeight;
        fittedTo = width + 'x' + height;
        var scale;
        if (fit === 'width') {
            scale = height / designHeight;
            stage.style.width = (width / scale) + 'px';
            stage.style.height = designHeight + 'px';
            stage.style.transform = 'scale(' + scale + ')';
        } else {
            scale = Math.min(width / designWidth, height / designHeight);
            var spareX = width - designWidth * scale;
            var spareY = height - designHeight * scale;
            var left = align.indexOf('left') >= 0 ? 0 : align.indexOf('right') >= 0 ? spareX : spareX / 2;
            var top = align.indexOf('top') >= 0 ? 0 : align.indexOf('bottom') >= 0 ? spareY : spareY / 2;
            stage.style.width = designWidth + 'px';
            stage.style.height = designHeight + 'px';
            stage.style.transform = 'translate(' + left + 'px,' + top + 'px) scale(' + scale + ')';
        }
        root.style.setProperty('--scale', String(scale));
        if (hooks.resize) hooks.resize();
    }

    window.addEventListener('resize', fitStage);
    fitStage();

    // --- Colours ---

    function rgbOf(hex) {
        var value = parseInt(hex.slice(1), 16);
        return [(value >> 16) & 255, (value >> 8) & 255, value & 255];
    }

    function hexOf(rgb) {
        return '#' + rgb.map(function (c) {
            var part = Math.max(0, Math.min(255, Math.round(c))).toString(16);
            return part.length < 2 ? '0' + part : part;
        }).join('');
    }

    function luminance(rgb) {
        return (0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]) / 255;
    }

    /** A colour bright enough to read and to glow against a dark strip: lifted towards white until it is. */
    function lifted(hex, least) {
        var rgb = rgbOf(hex);
        var guard = 0;
        while (luminance(rgb) < least && guard++ < 12) {
            rgb = rgb.map(function (c) { return c + (255 - c) * 0.18; });
        }
        return hexOf(rgb);
    }

    function setColour(name, hex) {
        root.style.setProperty('--' + name, hex);
        root.style.setProperty('--' + name + '-rgb', rgbOf(hex).join(','));
    }

    /**
     * The cover's two strongest colours, from a small copy of it drawn into a canvas: the pixels grouped by hue, each
     * counting for as much colour as it has, and the two heaviest groups averaged. Grey covers give greys.
     */
    function coloursOf(image) {
        try {
            var size = 24;
            var canvas = document.createElement('canvas');
            canvas.width = size;
            canvas.height = size;
            var context = canvas.getContext('2d');
            context.drawImage(image, 0, 0, size, size);
            var data = context.getImageData(0, 0, size, size).data;
            var groups = [];
            for (var g = 0; g < 13; g++) groups.push({ weight: 0, r: 0, g: 0, b: 0 });
            for (var i = 0; i < data.length; i += 4) {
                var r = data[i], gr = data[i + 1], b = data[i + 2];
                var max = Math.max(r, gr, b), min = Math.min(r, gr, b);
                var chroma = (max - min) / 255;
                var hue;
                if (chroma < 0.12) hue = 12;
                else if (max === r) hue = Math.floor(((((gr - b) / (max - min)) + 6) % 6) * 2);
                else if (max === gr) hue = Math.floor((((b - r) / (max - min)) + 2) * 2);
                else hue = Math.floor((((r - gr) / (max - min)) + 4) * 2);
                hue = Math.max(0, Math.min(12, hue));
                var weight = hue === 12 ? 0.15 : chroma * (0.4 + max / 255);
                var group = groups[hue];
                group.weight += weight;
                group.r += r * weight;
                group.g += gr * weight;
                group.b += b * weight;
            }
            groups.sort(function (a, b2) { return b2.weight - a.weight; });
            var picked = groups.filter(function (one) { return one.weight > 0; }).slice(0, 2).map(function (one) {
                return hexOf([one.r / one.weight, one.g / one.weight, one.b / one.weight]);
            });
            if (picked.length === 0) return null;
            if (picked.length === 1) picked.push(picked[0]);
            return picked;
        } catch (unreadable) {
            return null;
        }
    }

    // --- Writing the song into the page ---

    function fill(name, text) {
        var elements = document.querySelectorAll('[data-text="' + name + '"]');
        for (var i = 0; i < elements.length; i++) {
            if (elements[i].textContent !== text) elements[i].textContent = text;
        }
    }

    function clock(ms) {
        var total = Math.max(0, Math.floor(ms / 1000));
        var hours = Math.floor(total / 3600);
        var minutes = Math.floor((total % 3600) / 60);
        var seconds = total % 60;
        var mm = hours > 0 && minutes < 10 ? '0' + minutes : String(minutes);
        return (hours > 0 ? hours + ':' : '') + mm + ':' + (seconds < 10 ? '0' : '') + seconds;
    }

    var covers = {};

    /** The song's cover as a blob address, fetched once; null when it has none, or it did not come in time. */
    function coverFor(track) {
        if (!track.artwork) return Promise.resolve(null);
        if (covers[track.key]) return covers[track.key];
        var loading = fetch(track.artwork, { cache: 'no-store' })
            .then(function (reply) {
                if (!reply.ok) throw new Error('no cover');
                return reply.blob();
            })
            .then(function (blob) {
                var address = URL.createObjectURL(blob);
                return new Promise(function (resolve) {
                    var image = new Image();
                    image.onload = function () { resolve({ address: address, image: image }); };
                    image.onerror = function () { resolve(null); };
                    image.src = address;
                });
            })
            .catch(function () { return null; });
        covers[track.key] = loading;
        return loading;
    }

    function forgetCoversBut(key) {
        Object.keys(covers).forEach(function (other) {
            if (other === key) return;
            var going = covers[other];
            delete covers[other];
            going.then(function (cover) { if (cover) setTimeout(function () { URL.revokeObjectURL(cover.address); }, 4000); });
        });
    }

    function within(ms, promise) {
        return Promise.race([promise, new Promise(function (resolve) { setTimeout(function () { resolve(null); }, ms); })]);
    }

    function wait(ms) {
        return new Promise(function (resolve) { setTimeout(resolve, ms); });
    }

    var artColours = null;

    function show(track, cover) {
        fill('title', track.title);
        fill('artist', track.artist);
        fill('album', track.album || '');
        fill('line', track.artist ? track.artist + ' — ' + track.title : track.title);
        fill('service', track.service.name);
        root.classList.toggle('no-album', !track.album);

        var marks = document.querySelectorAll('[data-mark]');
        for (var i = 0; i < marks.length; i++) {
            var viewBox = '0 0 ' + track.service.viewBox + ' ' + track.service.viewBox;
            marks[i].setAttribute('viewBox', viewBox);
            var path = marks[i].querySelector('path');
            if (path) path.setAttribute('d', track.service.mark);
        }
        setColour('service', track.service.colour);
        root.classList.toggle('service-dark', luminance(rgbOf(track.service.colour)) < 0.12);
        root.setAttribute('data-service', track.service.id);

        var images = document.querySelectorAll('img[data-cover]');
        for (var j = 0; j < images.length; j++) {
            if (cover) images[j].src = cover.address;
            else images[j].removeAttribute('src');
        }
        root.classList.toggle('no-art', !cover);
        artColours = cover ? coloursOf(cover.image) : null;
        if (artColours) {
            setColour('art1', artColours[0]);
            setColour('art2', artColours[1]);
        } else {
            ['--art1', '--art1-rgb', '--art2', '--art2-rgb'].forEach(function (name) { root.style.removeProperty(name); });
        }
        paintAccent();
        if (hooks.track) hooks.track(track, cover);
    }

    // --- Following what is playing ---

    var current = null;
    var receivedAt = 0;
    var wantedKey = null;
    var shownKey = null;
    var pausedSince = 0;
    var failures = 0;
    var options = {};

    function paintAccent() {
        var chosen = colour('accent');
        if (!chosen && current && current.accentFromArtwork && artColours) chosen = artColours[0];
        if (!chosen) chosen = (current && current.accent) || root.getAttribute('data-accent') || '#b47cff';
        setColour('accent', lifted(chosen, 0.32));
    }

    function apply(reply) {
        current = reply;
        receivedAt = performance.now();
        failures = 0;
        var settings = reply.options || {};
        options = {
            cover: flag('cover', settings.cover !== false),
            progress: flag('progress', settings.progress !== false),
            logo: flag('logo', settings.logo !== false),
            hidePaused: flag('hidePaused', settings.hidePaused !== false),
            hideAfter: number('hideAfter', typeof settings.hideAfter === 'number' ? settings.hideAfter : 5)
        };
        root.classList.toggle('no-cover', !options.cover);
        root.classList.toggle('no-progress', !options.progress || !(reply.durationMs > 0));
        root.classList.toggle('no-duration', !(reply.durationMs > 0));
        root.classList.toggle('no-logo', !options.logo);
        root.classList.toggle('is-playing', !!reply.playing);
        root.classList.toggle('is-paused', reply.state === 'paused');
        root.classList.toggle('is-loading', reply.state === 'loading');
        if (reply.state === 'paused') {
            if (!pausedSince) pausedSince = Date.now();
        } else {
            pausedSince = 0;
        }
        fill('status', reply.state === 'paused' ? 'Paused' : 'Now playing');
        fill('duration', clock(reply.durationMs));
        paintAccent();

        var track = reply.track;
        if (track && track.key !== wantedKey) changeTo(track);
        else if (track && track.key === shownKey) {
            // The same song, perhaps renamed by the listener while it plays: written in place, without a change.
            fill('title', track.title);
            fill('artist', track.artist);
            fill('line', track.artist ? track.artist + ' — ' + track.title : track.title);
        }
        if (!track) wantedKey = null;
        updateVisibility();
        if (hooks.state) hooks.state(reply, options);
    }

    function changeTo(track) {
        wantedKey = track.key;
        var arriving = coverFor(track);
        forgetCoversBut(track.key);
        var wasShowing = shownKey !== null && !root.classList.contains('is-hidden');
        if (!wasShowing) {
            within(COVER_WAIT_MS, arriving).then(function (cover) {
                if (wantedKey !== track.key) return;
                show(track, cover);
                shownKey = track.key;
                updateVisibility();
            });
            return;
        }
        root.classList.remove('is-arriving');
        root.classList.add('is-leaving');
        Promise.all([within(COVER_WAIT_MS, arriving), wait(leaveMs)]).then(function (ready) {
            if (wantedKey !== track.key) return;
            show(track, ready[0]);
            shownKey = track.key;
            root.classList.remove('is-leaving');
            // Read once so the browser has the page as it was before arriving begins, and the change is animated.
            void stage.offsetWidth;
            root.classList.add('is-arriving');
            setTimeout(function () { if (shownKey === track.key) root.classList.remove('is-arriving'); }, arriveMs);
        });
    }

    function updateVisibility() {
        var hidden = !current || !current.track || shownKey === null || failures >= FAILURES_BEFORE_HIDING;
        if (!hidden && current.state === 'paused' && options.hidePaused && pausedSince &&
            Date.now() - pausedSince >= options.hideAfter * 1000) {
            hidden = true;
        }
        if (hidden && current && !current.track) shownKey = null;
        root.classList.toggle('is-hidden', hidden);
    }

    var lastSecond = -1;

    function frame() {
        // A page in a frame can be laid out at a frame's default size first and given its own without a resize event,
        // so the size is looked at every frame as well, and the page fitted again whenever it has changed.
        if ((window.innerWidth || designWidth) + 'x' + (window.innerHeight || designHeight) !== fittedTo) fitStage();
        if (current && current.track) {
            var duration = current.durationMs || 0;
            var position = current.positionMs + (current.playing ? performance.now() - receivedAt : 0);
            if (duration > 0) position = Math.min(position, duration);
            var fraction = duration > 0 ? Math.max(0, Math.min(1, position / duration)) : 0;
            var bars = document.querySelectorAll('[data-progress]');
            for (var i = 0; i < bars.length; i++) bars[i].style.transform = 'scaleX(' + fraction.toFixed(5) + ')';
            var second = Math.floor(position / 1000);
            if (second !== lastSecond) {
                lastSecond = second;
                fill('elapsed', clock(position));
            }
            if (hooks.progress) hooks.progress(fraction, position, duration);
        }
        updateVisibility();
        window.requestAnimationFrame(frame);
    }

    function poll() {
        fetch('/api/now-playing', { cache: 'no-store' })
            .then(function (reply) {
                if (!reply.ok) throw new Error('HTTP ' + reply.status);
                return reply.json();
            })
            .then(apply)
            .catch(function () {
                failures++;
                updateVisibility();
            })
            .then(function () { setTimeout(poll, failures ? RETRY_MS : POLL_MS); });
    }

    window.noctorium = { clock: clock, coloursOf: coloursOf, lifted: lifted, setColour: setColour };
    root.classList.add('is-hidden');
    poll();
    window.requestAnimationFrame(frame);
})();
