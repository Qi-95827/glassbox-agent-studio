package dev.workbench.orchestration;

import java.util.LinkedHashMap;
import java.util.Map;

/** 多文件冒泡排序样本：三份空白跟踪文件由脚本模型按授权写入真实内容。 */
final class BubbleSortFixture {
  private BubbleSortFixture() {}

  static Map<String, String> contents() {
    var contents = new LinkedHashMap<String, String>();
    contents.put("sort.js", SORT_JS);
    contents.put("index.html", INDEX_HTML);
    contents.put("style.css", STYLE_CSS);
    return Map.copyOf(contents);
  }

  static final String SORT_JS =
      """
      (function () {
        function bubbleSteps(values) {
          var items = values.slice();
          var order = items.map(function (_, index) { return index; });
          var steps = [];
          for (var end = items.length - 1; end > 0; end--) {
            for (var i = 0; i < end; i++) {
              var swapped = false;
              if (items[i] > items[i + 1]) {
                var value = items[i];
                items[i] = items[i + 1];
                items[i + 1] = value;
                var position = order[i];
                order[i] = order[i + 1];
                order[i + 1] = position;
                swapped = true;
              }
              steps.push({ values: items.slice(), order: order.slice(), compare: [i, i + 1], swapped: swapped, done: false });
            }
          }
          steps.push({ values: items.slice(), order: order.slice(), compare: [], swapped: false, done: true });
          return steps;
        }
        window.bubbleSteps = bubbleSteps;
      })();
      """;

  static final String INDEX_HTML =
      """
      <!doctype html>
      <html lang="zh-CN">
      <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>冒泡排序演示</title>
        <link rel="stylesheet" href="style.css">
      </head>
      <body>
        <main>
          <h1>冒泡排序演示</h1>
          <label for="values">数组（逗号分隔）</label>
          <input id="values" value="5,1,4,2,8" autocomplete="off">
          <div class="actions">
            <button id="start">开始</button>
            <button id="step">下一步</button>
            <button id="play">自动播放</button>
            <button id="pause" disabled>暂停</button>
            <button id="reset">重置</button>
          </div>
          <p id="message" role="status">输入数组后点开始。</p>
          <div id="bars" class="bars" aria-label="排序柱状图"></div>
        </main>
        <script src="sort.js"></script>
        <script>
          (function () {
            var steps = [];
            var index = 0;
            var timer = null;
            var bars = document.getElementById('bars');
            var message = document.getElementById('message');
            var valuesInput = document.getElementById('values');
            var play = document.getElementById('play');
            var pause = document.getElementById('pause');
            function parse() {
              var raw = valuesInput.value.split(',').map(function (item) { return item.trim(); }).filter(function (item) { return item.length > 0; });
              if (raw.length < 1 || raw.length > 12) return null;
              var values = [];
              for (var i = 0; i < raw.length; i++) {
                if (!/^-?\\d+$/.test(raw[i])) return null;
                values.push(Number(raw[i]));
              }
              return values;
            }
            function render(step) {
              bars.textContent = '';
              var maximum = Math.max.apply(null, step.values.map(function (value) { return Math.abs(value); }).concat([1]));
              step.values.forEach(function (value, position) {
                var bar = document.createElement('div');
                bar.className = 'bar';
                if (step.compare.indexOf(position) >= 0) bar.className += ' compared';
                if (step.done) bar.className += ' done';
                bar.style.height = Math.max(16, Math.round(Math.abs(value) / maximum * 180)) + 'px';
                bar.dataset.value = String(value);
                bar.title = String(value);
                var label = document.createElement('span');
                label.textContent = String(value);
                bar.appendChild(label);
                bars.appendChild(bar);
              });
              if (step.done) {
                message.textContent = '排序完成：' + step.values.join(',');
              } else if (step.swapped) {
                message.textContent = '交换位置 ' + (step.compare[0] + 1) + ' 与 ' + (step.compare[1] + 1) + '，当前：' + step.values.join(',');
              } else {
                message.textContent = '比较位置 ' + (step.compare[0] + 1) + ' 与 ' + (step.compare[1] + 1) + '，无需交换。';
              }
            }
            function stop() {
              if (timer) { clearInterval(timer); timer = null; }
              play.disabled = false;
              pause.disabled = true;
            }
            function start() {
              stop();
              var values = parse();
              if (!values) {
                steps = [];
                bars.textContent = '';
                message.textContent = '请输入 1 至 12 个整数，用逗号分隔。';
                return;
              }
              steps = window.bubbleSteps(values);
              index = 0;
              message.textContent = '共 ' + (steps.length - 1) + ' 次比较；点下一步或自动播放。';
              render(steps[0]);
            }
            document.getElementById('start').addEventListener('click', start);
            document.getElementById('step').addEventListener('click', function () {
              if (!steps.length) { start(); return; }
              if (index >= steps.length - 1) { message.textContent = '已经到达最后一步。'; return; }
              index += 1;
              render(steps[index]);
            });
            play.addEventListener('click', function () {
              if (!steps.length) start();
              if (!steps.length || index >= steps.length - 1) return;
              stop();
              play.disabled = true;
              pause.disabled = false;
              timer = setInterval(function () {
                if (index >= steps.length - 1) { stop(); return; }
                index += 1;
                render(steps[index]);
              }, 450);
            });
            pause.addEventListener('click', function () {
              var at = message.textContent;
              stop();
              message.textContent = '已暂停：' + at;
            });
            document.getElementById('reset').addEventListener('click', function () {
              stop();
              steps = [];
              index = 0;
              valuesInput.value = '5,1,4,2,8';
              bars.textContent = '';
              message.textContent = '输入数组后点开始。';
            });
          })();
        </script>
      </body>
      </html>
      """;

  static final String STYLE_CSS =
      """
      * { box-sizing: border-box; }
      body {
        margin: 0;
        font-family: system-ui, -apple-system, "Segoe UI", sans-serif;
        background: #f5f7fb;
        color: #1f2937;
      }
      main { max-width: 860px; margin: 0 auto; padding: 32px 20px 56px; }
      h1 { font-size: 24px; margin: 0 0 18px; }
      label { display: block; font-size: 13px; color: #4b5563; margin-bottom: 6px; }
      input {
        width: 100%; padding: 10px 12px; font-size: 16px;
        border: 1px solid #cbd5e1; border-radius: 8px; background: #fff;
      }
      .actions { display: flex; flex-wrap: wrap; gap: 8px; margin: 14px 0; }
      button {
        padding: 8px 14px; font-size: 14px; border-radius: 8px;
        border: 1px solid #0b57d0; background: #0b57d0; color: #fff; cursor: pointer;
      }
      button:disabled { background: #c7d2fe; border-color: #c7d2fe; cursor: default; }
      #bars {
        display: flex; align-items: flex-end; gap: 10px;
        min-height: 210px; padding: 16px; margin-top: 12px;
        background: #fff; border: 1px solid #e2e8f0; border-radius: 12px;
      }
      .bar {
        flex: 1 1 0; min-width: 24px; display: flex; align-items: flex-end;
        justify-content: center; background: #93c5fd; border-radius: 6px 6px 0 0;
        transition: height 180ms ease, background 180ms ease;
      }
      .bar.compared { background: #f59e0b; }
      .bar.done { background: #22c55e; }
      .bar span { font-size: 12px; padding-bottom: 4px; color: #0f172a; }
      #message { min-height: 24px; margin: 12px 0 0; color: #334155; }
      """;
}
