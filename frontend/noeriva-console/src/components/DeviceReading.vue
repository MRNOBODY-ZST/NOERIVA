<script setup lang="ts">
import { computed } from "vue";
import type { Reading } from "../services/devices";
import { usePreferencesStore } from "../stores/preferences";
import {
  formatTime,
  formatRate,
  formatMetric,
  naturalNameSort,
  identityValue,
} from "../utils/format";
import StatusBadge from "./StatusBadge.vue";
const props = defineProps<{ reading: Reading }>();
const prefs = usePreferencesStore();
const identity = computed(() => props.reading.identity);
const ports = computed(() => naturalNameSort(props.reading.ports || []));
const excludedSensorIds = computed(
  () =>
    new Set(
      (props.reading.facts?.healthExcludedSensorIds || "")
        .split(",")
        .map((id) => id.trim()),
    ),
);
const metricNames: Record<string, string> = {
  cpu_percent: "CPU 使用率",
  memory_percent: "内存使用率",
  temperature_celsius: "温度",
  power_watts: "功率",
  bandwidth_rx_bps: "接收带宽",
  bandwidth_tx_bps: "发送带宽",
  fan_rpm: "风扇转速",
  fan_percent: "风扇速度百分比",
  voltage_volts: "电压",
  current_amperes: "电流",
  current_amps: "电流",
  sensor_state: "离散状态",
  temperature: "温度",
  power: "功率",
  voltage: "电压",
  current: "电流",
  fanSpeed: "风扇速度",
  percent: "百分比读数",
  energy: "能量",
  componentHealth: "组件健康",
};
const metricUnits: Record<string, string> = {
  cpu_percent: "%",
  memory_percent: "%",
  temperature_celsius: "°C",
  power_watts: "W",
  bandwidth_rx_bps: "bit/s",
  bandwidth_tx_bps: "bit/s",
  fan_rpm: "转/分钟",
  fan_percent: "%",
  voltage_volts: "V",
  current_amperes: "A",
  current_amps: "A",
};
const metricName = (id: string) => metricNames[id] || id;
const unitName = (unit: string) =>
  unit === "Cel" ? "°C" : unit === "RPM" ? "转/分钟" : unit;
</script>
<template>
  <div class="panel-body device-reading">
    <div class="wb-inline">
      <span class="small muted">当次观测健康</span
      ><StatusBadge :status="reading.health" /><span class="small muted"
        >观测 {{ formatTime(reading.observedAt, prefs.timezone) }}</span
      >
    </div>
    <dl class="wb-facts wb-spaced">
      <dt>识别品牌 / 系列</dt>
      <dd>
        {{ identity?.vendor || "未知" }} / {{ identity?.family || "通用设备" }}
      </dd>
      <dt>型号</dt>
      <dd>{{ identity?.model || "设备未提供" }}</dd>
      <dt>匹配档案</dt>
      <dd class="mono">{{ identity?.profileId || "generic" }}</dd>
      <dt>
        {{ reading.facts?.serialKind === "serviceTag" ? "服务标签" : "序列号" }}
        /
        {{
          identityValue(identity?.firmware) !== "未提供"
            ? "固件"
            : reading.facts?.softwareVersion
              ? "系统软件版本"
              : "固件"
        }}
      </dt>
      <dd>
        {{ identityValue(identity?.serialNumber) }} /
        {{
          identityValue(identity?.firmware) !== "未提供"
            ? identity?.firmware
            : identityValue(reading.facts?.softwareVersion)
        }}
      </dd>
      <dt>设备返回名称</dt>
      <dd>{{ identity?.sysName || "未提供" }}</dd>
      <dt>识别标识</dt>
      <dd class="mono">{{ identity?.sysObjectId || "未提供" }}</dd>
    </dl>
    <p v-if="identity?.description" class="small muted wb-spaced">
      {{ identity.description }}
    </p>
    <p class="wb-note">
      这里保留最后一次读取的历史值，不代表当前在线或健康状态。当前状态以设备概览的新鲜度与可用性为准。识别结果来自本次读取；登记品牌型号保持独立，可在资产资料中编辑。通用匹配不表示已验证全部厂商能力。
    </p>
    <div v-if="reading.qualityFlags?.length" class="notice">
      数据质量 · {{ reading.qualityFlags.join(" · ") }}
    </div>
    <p class="small wb-spaced">
      <strong>实际读取能力：</strong
      >{{ reading.capabilities?.join(" · ") || "尚无可确认能力" }}
    </p>
    <dl
      v-if="Object.keys(reading.metrics || {}).length"
      class="wb-facts wb-spaced"
    >
      <template v-for="(value, key) in reading.metrics" :key="key"
        ><dt :title="key">{{ metricName(key) }}</dt>
        <dd>
          {{
            value === null
              ? "缺失"
              : formatMetric(value, metricUnits[key] || "")
          }}
        </dd></template
      >
    </dl>
    <details v-if="reading.sensors?.length" class="device-reading-details">
      <summary>传感器 · {{ reading.sensors.length }} 项</summary>
      <div
        class="table-scroll"
        role="region"
        aria-label="实际传感器读数"
        tabindex="0"
      >
        <table class="data-table">
          <thead>
            <tr>
              <th>名称 / 来源</th>
              <th>指标</th>
              <th>数值</th>
              <th>健康</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in reading.sensors" :key="s.id">
              <td>
                {{ s.label }}
                <p class="secondary-line mono">{{ s.sourceRef }}</p>
              </td>
              <td :title="s.metric">{{ metricName(s.metric) }}</td>
              <td>
                <template
                  v-if="
                    s.value === null &&
                    s.metric === 'componentHealth' &&
                    s.unit === 'state'
                  "
                  >状态项</template
                >
                <template
                  v-else-if="
                    s.value === null &&
                    s.metric === 'sensor_state' &&
                    s.unit === 'discrete'
                  "
                  >离散状态项（未解码）</template
                >
                <template v-else>{{
                  s.value === null
                    ? "缺失"
                    : formatMetric(s.value, unitName(s.unit))
                }}</template>
              </td>
              <td>
                <StatusBadge :status="s.health" /><span
                  v-if="excludedSensorIds.has(s.id)"
                  class="secondary-line"
                  >接口已管理关闭 · 低阈值仅保留证据</span
                >
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </details>
    <details v-if="reading.ports?.length" class="device-reading-details">
      <summary>端口 · {{ reading.ports.length }} 项</summary>
      <div
        class="table-scroll"
        role="region"
        aria-label="实际端口读数"
        tabindex="0"
      >
        <table class="data-table">
          <thead>
            <tr>
              <th>端口 / 来源</th>
              <th>管理 / 运行</th>
              <th>标称速率</th>
              <th>输入 / 输出字节计数</th>
              <th>计数器</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="port in ports" :key="port.key">
              <td>
                {{ port.name }}
                <p class="secondary-line mono">{{ port.sourceRef }}</p>
              </td>
              <td>{{ port.adminStatus }} / {{ port.operStatus }}</td>
              <td>
                {{
                  port.speedBps === null
                    ? "未知"
                    : formatRate(Number(port.speedBps))
                }}
              </td>
              <td class="mono">
                {{ port.inOctets ?? "缺失" }} / {{ port.outOctets ?? "缺失" }}
              </td>
              <td>
                {{ port.counterBits }} bit
                <p class="secondary-line">
                  discontinuity {{ port.discontinuity ?? "未知" }}
                </p>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </details>
    <details
      v-if="Object.keys(reading.facts || {}).length"
      class="device-reading-details"
    >
      <summary>识别依据</summary>
      <dl class="wb-facts">
        <template v-for="(value, key) in reading.facts" :key="key"
          ><dt>{{ key }}</dt>
          <dd class="mono">{{ value }}</dd></template
        >
      </dl>
    </details>
  </div>
</template>
