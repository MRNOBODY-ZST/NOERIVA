<script setup lang="ts">
import { ShieldCheck } from "@lucide/vue";
import { useApiQuery } from "../services/queries";
import type { Capability, Page } from "../services/types";
import QueryState from "../components/QueryState.vue";
import { useSessionStore } from "../stores/session";
const auth = useSessionStore();
const { data, isPending, error, refetch } =
  useApiQuery<Page<Capability>>("/capabilities");
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>系统能力</h1>
      <p>区分已实现的软件能力、合成验证与真实设备支持。</p>
    </div>
    <span class="tag">{{ auth.session?.mode }}</span>
  </div>
  <div class="notice" style="margin-bottom: 20px">
    <ShieldCheck
      aria-hidden="true"
      :size="16"
      style="display: inline; vertical-align: -3px; margin-right: 6px"
    /><strong>实现状态以服务端能力矩阵为准。</strong>
    页面存在、演示数据和技术规范均不等于已验证真实协议支持。
  </div>
  <section class="panel">
    <header class="panel-head">
      <h2>能力矩阵</h2>
      <span class="small muted">独立状态维度 · 服务端返回</span>
    </header>
    <QueryState
      :pending="isPending"
      :error="error"
      :empty="!data?.items.length"
      @retry="refetch()"
      ><div
        class="table-scroll"
        role="region"
        aria-label="能力状态矩阵，可横向滚动"
        tabindex="0"
      >
        <table class="data-table">
          <thead>
            <tr>
              <th>能力</th>
              <th>路线图层级</th>
              <th>成熟度</th>
              <th>覆盖</th>
              <th>验证</th>
              <th>阶段</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in data?.items" :key="item.id">
              <td class="text-wrap">
                <strong style="font-weight: 550">{{ item.name }}</strong>
                <div class="secondary-line">{{ item.notes }}</div>
              </td>
              <td>
                <span class="coverage-label">{{ item.roadmapTier }}</span>
              </td>
              <td class="small">{{ item.maturity }}</td>
              <td class="small">{{ item.coverage }}</td>
              <td class="small">{{ item.verification }}</td>
              <td>{{ item.phase }}</td>
            </tr>
          </tbody>
        </table>
      </div></QueryState
    >
  </section>
  <div class="panel section-gap">
    <header class="panel-head"><h2>当前会话</h2></header>
    <dl class="panel-body three-col">
      <div class="key-value">
        <dt>账号</dt>
        <dd>{{ auth.session?.username }}</dd>
      </div>
      <div class="key-value">
        <dt>组织</dt>
        <dd>{{ auth.session?.organizationId }}</dd>
      </div>
      <div class="key-value">
        <dt>角色</dt>
        <dd>{{ auth.session?.roles.join(" · ") }}</dd>
      </div>
    </dl>
    <footer class="panel-foot">
      权限由服务端执行。浏览器仅在内存中保存会话令牌，服务端校验工作区与角色。SSO
      和细粒度站点授权管理界面尚未提供。
    </footer>
  </div>
</template>
