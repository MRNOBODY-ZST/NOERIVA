<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useSystemSettings } from "../services/settings";
import { request, ApiError } from "../services/api";
import type { ConnectionView, Slot } from "../services/devices";
const props = defineProps<{
  deviceId: string;
  slot: Slot;
  managementAddress: string;
  connection: ConnectionView | null;
}>();
const emit = defineEmits<{ saved: [value: ConnectionView]; cancel: [] }>();
const previous = props.connection;
const form = ref({
  revision: previous?.revision || 0,
  host: previous?.host || props.managementAddress,
  port:
    previous?.port ||
    (props.slot === "snmp" ? 161 : props.slot === "ssh" ? 22 : 443),
  enabled: false,
  intervalSeconds: previous?.intervalSeconds || 60,
  timeoutMillis: previous?.timeoutMillis || 3000,
  maxInterfaces: previous?.maxInterfaces || 128,
  username: previous?.username || "",
  snmpVersion: previous?.snmpVersion || "3",
  securityLevel: previous?.securityLevel || "authPriv",
  authProtocol: previous?.authProtocol || "SHA256",
  privacyProtocol: previous?.privacyProtocol || "AES128",
  contextName: previous?.contextName || "",
  tlsMode: previous?.tlsMode || "SYSTEM",
  certificateSha256: previous?.certificateSha256 || "",
  sshProfile: previous?.sshProfile || "",
  sshHostKeySha256: previous?.sshHostKeySha256 || "",
});
const defaults = useSystemSettings(() => !previous);
const defaultsDirty = ref(false);
watch(
  defaults.data,
  (value) => {
    if (!previous && value && !defaultsDirty.value) {
      form.value.intervalSeconds = value.defaultCollectionIntervalSeconds;
      form.value.timeoutMillis = value.defaultTimeoutMillis;
      form.value.maxInterfaces = value.defaultMaxInterfaces;
    }
  },
  { immediate: true },
);
const element = ref<HTMLFormElement>(),
  pending = ref(false),
  error = ref("");
function clearSecrets() {
  element.value
    ?.querySelectorAll<HTMLInputElement>('input[type="password"]')
    .forEach((input) => (input.value = ""));
}
onBeforeUnmount(clearSecrets);
watch(
  () => [
    form.value.snmpVersion,
    form.value.securityLevel,
    form.value.sshProfile,
  ],
  clearSecrets,
);
function credentialIdentity(
  value: Pick<
    ConnectionView,
    | "host"
    | "port"
    | "username"
    | "snmpVersion"
    | "securityLevel"
    | "authProtocol"
    | "privacyProtocol"
    | "contextName"
    | "tlsMode"
    | "certificateSha256"
    | "sshHostKeySha256"
  > & { sshProfile: string | null },
) {
  const target = [value.host, value.port];
  if (props.slot === "ssh")
    return [
      ...target,
      value.username.trim(),
      value.sshProfile || "",
      (value.sshHostKeySha256 || "").trim(),
    ];
  if (props.slot === "redfish")
    return [
      ...target,
      value.username.trim(),
      value.tlsMode,
      value.tlsMode === "PINNED"
        ? value.certificateSha256.trim().replaceAll(":", "").toLowerCase()
        : "",
    ];
  if (value.snmpVersion === "2c") return [...target, "2c"];
  return [
    ...target,
    "3",
    value.username.trim(),
    value.securityLevel,
    value.securityLevel === "noAuthNoPriv" ? "" : value.authProtocol,
    value.securityLevel === "authPriv" ? value.privacyProtocol : "",
    value.contextName.trim(),
  ];
}
const changed = computed(
  () =>
    !previous ||
    JSON.stringify(credentialIdentity(form.value)) !==
      JSON.stringify(credentialIdentity(previous)),
);
const fields = computed(() => {
  if (props.slot === "redfish" || props.slot === "ssh")
    return [
      {
        name: "password",
        label: props.slot === "ssh" ? "SSH 密码" : "Redfish 密码",
        required: changed.value || !previous?.hasPassword,
      },
    ];
  if (form.value.snmpVersion === "2c")
    return [
      {
        name: "community",
        label: "只读 Community",
        required: changed.value || !previous?.hasCommunity,
      },
    ];
  return [
    ...(form.value.securityLevel !== "noAuthNoPriv"
      ? [
          {
            name: "authPassword",
            label: "认证密码",
            required: changed.value || !previous?.hasAuthPassword,
          },
        ]
      : []),
    ...(form.value.securityLevel === "authPriv"
      ? [
          {
            name: "privacyPassword",
            label: "加密密码",
            required: changed.value || !previous?.hasPrivacyPassword,
          },
        ]
      : []),
  ];
});
async function save() {
  pending.value = true;
  error.value = "";
  if (props.slot === "ssh") {
    if (
      !/^SHA256:[A-Za-z0-9+/]{42}[AEIMQUYcgkosw048]$/.test(
        form.value.sshHostKeySha256.trim(),
      )
    ) {
      error.value =
        "请填写规范的主机密钥 SHA-256 指纹（SHA256: 后接无填充 Base64）。";
      pending.value = false;
      return;
    }
    if (
      !["HUAWEI_IMANA", "DELL_OS9", "CISCO_IOS_XE"].includes(
        form.value.sshProfile,
      )
    ) {
      error.value = "请选择 SSH 只读采集档案。";
      pending.value = false;
      return;
    }
  }
  const data = new FormData(element.value);
  const secrets: Record<string, string | null> = {
    community: null,
    authPassword: null,
    privacyPassword: null,
    password: null,
  };
  for (const field of fields.value) {
    const value = String(data.get(field.name) || "");
    if (field.required && !value) {
      error.value = `请重新填写${field.label}。`;
      pending.value = false;
      return;
    }
    if (
      ["authPassword", "privacyPassword"].includes(field.name) &&
      value &&
      value.length < 8
    ) {
      error.value = `${field.label}至少 8 个字符。`;
      pending.value = false;
      return;
    }
    secrets[field.name] = value || null;
  }
  const body = JSON.stringify({
    ...form.value,
    sshProfile: props.slot === "ssh" ? form.value.sshProfile : null,
    sshHostKeySha256:
      props.slot === "ssh" ? form.value.sshHostKeySha256.trim() : null,
    secrets,
  });
  clearSecrets();
  try {
    const value = await request<ConnectionView>(
      `/devices/${encodeURIComponent(props.deviceId)}/connections/${props.slot}`,
      { method: "POST", body },
    );
    emit("saved", value);
  } catch (e) {
    error.value =
      e instanceof ApiError && e.status === 409
        ? "连接版本已变化，请取消后重新打开编辑。"
        : e instanceof ApiError && e.status === 503
          ? "凭据存储尚未配置或服务暂不可用，请联系管理员。"
          : "连接保存失败，请检查设置并重新输入所需密钥。";
  } finally {
    pending.value = false;
  }
}
</script>
<template>
  <form
    ref="element"
    class="wb-form"
    autocomplete="off"
    @input="defaultsDirty = true"
    @submit.prevent="save"
  >
    <p class="wb-note">
      连接目标独立于资产地址。保存连接后需重新测试；自动采集保持停用。已保存的密钥不回显，留空保留原值；首次配置或更改连接身份需重新填写。
    </p>
    <div class="form-grid">
      <div class="form-field">
        <label :for="`${slot}-host`">连接地址</label
        ><input
          :id="`${slot}-host`"
          v-model="form.host"
          class="input mono"
          required
          maxlength="253"
        />
      </div>
      <div class="form-field">
        <label :for="`${slot}-port`">端口</label
        ><input
          :id="`${slot}-port`"
          v-model.number="form.port"
          class="input"
          type="number"
          min="1"
          max="65535"
          required
        />
      </div>
    </div>
    <template v-if="slot === 'snmp'"
      ><div class="form-grid">
        <div class="form-field">
          <label for="snmp-version">SNMP 版本</label
          ><select id="snmp-version" v-model="form.snmpVersion" class="input">
            <option value="3">SNMPv3</option>
            <option value="2c">SNMPv2c</option>
          </select>
        </div>
        <div v-if="form.snmpVersion === '3'" class="form-field">
          <label for="snmp-security">安全级别</label
          ><select
            id="snmp-security"
            v-model="form.securityLevel"
            class="input"
          >
            <option value="authPriv">认证与加密</option>
            <option value="authNoPriv">仅认证</option>
            <option value="noAuthNoPriv">无认证无加密</option>
          </select>
        </div>
      </div>
      <p
        v-if="
          form.snmpVersion === '2c' || form.securityLevel === 'noAuthNoPriv'
        "
        class="notice"
      >
        当前协议设置不提供加密保护，请确认设备网络访问范围。
      </p>
      <div v-if="form.snmpVersion === '3'" class="form-grid">
        <div class="form-field">
          <label for="snmp-username">SNMPv3 用户名</label
          ><input
            id="snmp-username"
            v-model="form.username"
            class="input"
            maxlength="120"
            required
            autocomplete="off"
          />
        </div>
        <div class="form-field">
          <label for="snmp-context">Context（可选）</label
          ><input
            id="snmp-context"
            v-model="form.contextName"
            class="input"
            maxlength="64"
          />
        </div>
        <div v-if="form.securityLevel !== 'noAuthNoPriv'" class="form-field">
          <label for="snmp-auth">认证算法</label
          ><select id="snmp-auth" v-model="form.authProtocol" class="input">
            <option value="SHA256">SHA-256</option>
            <option value="SHA512">SHA-512</option>
            <option value="SHA1">SHA-1（旧设备兼容）</option>
            <option value="MD5">MD5（旧设备兼容）</option>
          </select>
        </div>
        <div v-if="form.securityLevel === 'authPriv'" class="form-field">
          <label for="snmp-privacy">加密算法</label
          ><select
            id="snmp-privacy"
            v-model="form.privacyProtocol"
            class="input"
          >
            <option value="AES128">AES-128</option>
            <option value="DES">DES（旧设备兼容）</option>
          </select>
        </div>
      </div></template
    >
    <template v-else-if="slot === 'redfish'"
      ><div class="form-grid">
        <div class="form-field">
          <label for="redfish-username">Redfish 用户名</label
          ><input
            id="redfish-username"
            v-model="form.username"
            class="input"
            required
            maxlength="120"
            autocomplete="off"
          />
        </div>
        <div class="form-field">
          <label for="redfish-tls">HTTPS 证书验证</label
          ><select id="redfish-tls" v-model="form.tlsMode" class="input">
            <option value="SYSTEM">系统信任证书</option>
            <option value="PINNED">固定 SHA-256 指纹</option>
          </select>
        </div>
      </div>
      <div v-if="form.tlsMode === 'PINNED'" class="form-field">
        <label for="redfish-fingerprint">叶证书 SHA-256 指纹</label
        ><input
          id="redfish-fingerprint"
          v-model="form.certificateSha256"
          class="input mono"
          required
          pattern="(?:[a-fA-F0-9]{64}|(?:[a-fA-F0-9]{2}:){31}[a-fA-F0-9]{2})"
          placeholder="64 位十六进制，可用冒号分隔"
        /></div
    ></template>
    <template v-else-if="slot === 'ssh'">
      <div class="form-grid">
        <div class="form-field">
          <label for="ssh-username">SSH 用户名</label>
          <input
            id="ssh-username"
            v-model="form.username"
            class="input"
            required
            maxlength="120"
            autocomplete="off"
          />
        </div>
        <div class="form-field">
          <label for="ssh-profile">只读采集档案</label>
          <select
            id="ssh-profile"
            v-model="form.sshProfile"
            class="input"
            required
          >
            <option value="" disabled>选择设备系列</option>
            <option value="HUAWEI_IMANA">Huawei iMana</option>
            <option value="DELL_OS9">Dell Networking OS9</option>
            <option value="CISCO_IOS_XE">Cisco IOS XE</option>
          </select>
        </div>
      </div>
      <div class="form-field">
        <label for="ssh-fingerprint">主机密钥 SHA-256 指纹</label>
        <input
          id="ssh-fingerprint"
          v-model="form.sshHostKeySha256"
          class="input mono"
          required
          maxlength="50"
          pattern="SHA256:[A-Za-z0-9+\/]{42}[AEIMQUYcgkosw048]"
          placeholder="SHA256:…（无 = 填充）"
          aria-describedby="ssh-pin-help"
        />
        <p id="ssh-pin-help" class="small muted">
          从受信任设备控制台核对主机公钥指纹。校验通过后才认证，不接受任意主机密钥。
        </p>
      </div>
      <p class="wb-note">
        仅执行所选档案内置的只读命令；不提供任意命令、自动降级或配置下发。ARP/LLDP
        等事实在设备返回且档案支持时保存，供发现审核使用。
      </p>
    </template>
    <div class="form-grid">
      <div v-for="field in fields" :key="field.name" class="form-field">
        <label :for="`${slot}-${field.name}`"
          >{{ field.label
          }}{{ field.required ? " *" : "（已配置，可留空保留）" }}</label
        ><input
          :id="`${slot}-${field.name}`"
          :name="field.name"
          class="input"
          type="password"
          :required="field.required"
          :minlength="
            ['authPassword', 'privacyPassword'].includes(field.name) ? 8 : 1
          "
          maxlength="1024"
          autocomplete="new-password"
        />
      </div>
    </div>
    <div class="form-grid">
      <div class="form-field">
        <label :for="`${slot}-interval`">采集周期（秒）</label
        ><input
          :id="`${slot}-interval`"
          v-model.number="form.intervalSeconds"
          class="input"
          type="number"
          min="15"
          max="86400"
          required
        />
      </div>
      <div class="form-field">
        <label :for="`${slot}-timeout`">单次超时（毫秒）</label
        ><input
          :id="`${slot}-timeout`"
          v-model.number="form.timeoutMillis"
          class="input"
          type="number"
          min="250"
          max="10000"
          required
        />
      </div>
      <div class="form-field">
        <label :for="`${slot}-interfaces`">接口读取上限</label
        ><input
          :id="`${slot}-interfaces`"
          v-model.number="form.maxInterfaces"
          class="input"
          type="number"
          min="1"
          max="256"
          required
        />
      </div>
    </div>
    <p v-if="error" class="wb-error" role="alert">{{ error }}</p>
    <div class="actions">
      <button
        type="button"
        class="btn"
        :disabled="pending"
        @click="
          clearSecrets();
          emit('cancel');
        "
      >
        取消</button
      ><button class="btn primary" :disabled="pending">
        {{ pending ? "正在保存…" : "保存连接" }}
      </button>
    </div>
  </form>
</template>
