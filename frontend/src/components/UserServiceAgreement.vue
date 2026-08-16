<template>
  <Teleport to="body">
    <div v-if="modelValue" class="usa-mask" role="dialog" aria-modal="true" aria-labelledby="usa-title">
      <div class="usa-drawer">
        <header class="usa-header">
          <button type="button" class="usa-back" @click="closeWithoutConfirm">← 返回</button>
          <h1 id="usa-title">用户服务协议</h1>
          <button type="button" class="usa-close" aria-label="关闭" @click="closeWithoutConfirm">×</button>
        </header>
        <div class="usa-meta">更新日期：2026 年 5 月 8 日｜生效日期：请您首次注册或使用本服务之日</div>
        <article
          ref="bodyEl"
          class="usa-body"
          tabindex="0"
          @scroll.passive="checkScrollEnd"
        >
          <section>
            <h2>首部及说明</h2>
            <p>
              欢迎您使用<strong>ThreshCore</strong>（以下简称「本平台」）。本协议由您与平台运营方（以下简称「我们」）共同缔结，
              对双方具有约束力。请您在注册账号、勾选同意或开始使用服务前，认真阅读并充分理解本协议各条款，
              <strong>特别是与您权利义务、责任限制或免除、隐私与数据有关的条款（已以合理方式提请注意）</strong>。
              若您不同意本协议，请立即停止注册或使用；您一旦完成注册、登录或以其他方式实际使用本平台服务，即视为已阅读并同意受本协议约束。
            </p>
          </section>

          <section>
            <h2>定义</h2>
            <ul>
              <li><strong>用户</strong>：指注册或经授权使用本平台服务的自然人或其他主体。</li>
              <li><strong>服务内容</strong>：包括但不限于运维辅助、日志与分析、告警配置等功能（以实际开放的模块为准）。</li>
              <li><strong>账号</strong>：您用于登录本平台服务的唯一凭证组合（含用户名、密码及我们要求的安全校验方式）。</li>
            </ul>
          </section>

          <section>
            <h2>账号注册与安全</h2>
            <ol>
              <li>您应具备使用本服务的民事行为能力，并如实提供注册信息。</li>
              <li>
                为保护账号安全，我们可能<strong>按需</strong>在注册、登录或敏感操作等场景要求您完成额外校验（例如二次验证、设备校验等）；您应按届时页面提示操作。
              </li>
              <li>您对账号下的全部行为承担责任。请妥善保管密码及系统下发的各类安全凭证，不向他人出借、转让或以其他方式交由他人操控。</li>
              <li>如发现账号被盗或异常登录，应立即修改密码或通过我们认可的渠道与我们联系（若配置了相应入口）。必要时我们将采取限制登录、重置凭证等措施以降低风险。</li>
            </ol>
          </section>

          <section>
            <h2>服务内容与变更</h2>
            <p>
              本平台按「现状」和「可提供」的范围向您提供服务；我们可能因产品开发、运维、合规或安全需要对功能进行优化、迭代、限时维护或中止部分能力，
              并将尽可能通过页面提示或其他合理方式进行告知。如因维护或不可抗力导致短时不可用，我们会在合理限度内尽力恢复，但对因此造成的直接或间接损失在法律允许的范围内不承担责任，
              但因我们故意或重大过失导致的除外。
            </p>
          </section>

          <section>
            <h2>用户行为规范</h2>
            <p>在使用本平台期间，您承诺遵守法律法规及社会公序良俗，并不得从事包括但不限于下列行为：</p>
            <ol>
              <li>未经授权访问或试图破坏本平台及相关系统，或未经授权收集、窃取其他用户的信息；</li>
              <li>上传、导入、分发含有病毒、恶意代码或用于攻击、挖矿、爬虫滥用等破坏性用途的内容或数据；</li>
              <li>对本平台进行反向工程（法律强制允许的除外）、绕过安全措施、对他人进行骚扰或虚假宣传；</li>
              <li>利用本平台处理违反法律法规、侵犯他人合法权益的数据或业务（例如未经许可的监听、盗取商业秘密等）；</li>
              <li>大批量自动化请求等对系统稳定性造成实质性影响的行为。</li>
            </ol>
            <p>
              若您违反上述承诺，我们有权视情节采取<strong>警示、限制功能、中止或终止服务、移交相关部门</strong>等措施，并要求您赔偿因此给我们或第三方造成的损失。
            </p>
          </section>

          <section>
            <h2>数据、日志与隐私</h2>
            <ol>
              <li>
                您导入、上传或本平台为您处理的日志与分析数据主要用于<strong>您本人或您所在组织授权的运维与分析目的</strong>。
                您应确保对前述数据享有合法使用权，并负责对敏感信息做<strong>最小化上传、匿名化或脱敏</strong>后再使用本平台进行分析。
              </li>
              <li>
                除法律法规另有规定或为履行法定义务所必需的情形外，我们不会向无关第三方出售您的个人信息。
                为履行本协议，我们可能依法依规或按您指示进行必要的信息处理与安全审计；具体处理方式以届时有效的隐私政策或与您的书面约定为准（若单独的隐私政策已发布）。
              </li>
              <li>
                您同意我们基于保障账号安全与服务稳定的目的，依法依规留存<strong>与安全、审计有关的必要日志</strong>。
              </li>
            </ol>
          </section>

          <section>
            <h2>推送与告警</h2>
            <p>
              若您在平台内配置告警策略，告警仅在系统内记录与展示；请您自行妥善保管账号与审计数据。
              因第三方服务不可用、配置错误或网络原因导致的推送缺失，在法律允许范围内本平台不承担责任。
            </p>
          </section>

          <section>
            <h2>知识产权</h2>
            <p>
              本平台界面、文案、源代码、商标标识等知识产权均归 ThreshCore 项目团队或权利人所有，未经授权不得复制、传播或用于竞赛以外的商业用途。
            </p>
            <p>您在使用过程中自行生成的分析结果与工作成果，在您合法享有相关数据权利的范围内<strong>可由您依法依规使用</strong>。</p>
          </section>

          <section>
            <h2>免责声明（责任限制）</h2>
            <ol>
              <li>
                本平台所提供的分析结论、告警建议等内容<strong>仅供辅助决策参考</strong>，不构成任何形式的承诺与担保。
                您应结合自身业务与风险评估独立作出判断。
              </li>
              <li>
                因<strong>不可抗力、第三方服务中断、电信设备故障、您自身操作不当或未按安全配置使用</strong>等原因导致的后果，在法律允许的最大范围内本平台不承担责任。
              </li>
              <li>在任何情况下，若需承担赔偿责任，在法律允许范围内我们应承担的责任<strong>上限为您因本次争议事由向我们已实际支付的费用（通常为 0 元）</strong>；不适用该上限的情形以法律强制性规定为准。</li>
            </ol>
          </section>

          <section>
            <h2>协议更新与终止</h2>
            <p>
              我们可能根据需要修订本协议。重大变更将通过页面公示或其他合理方式进行提示；
              <strong>您继续使用本平台即视为接受修订后的条款</strong>。若修订导致您无法在合理条件下继续使用服务的，您可以停止使用并根据页面指引注销账号（若提供服务）。
            </p>
            <p>您可随时停止使用本平台；在满足法律法规与安全审计要求的前提下，您可申请注销账号。您严重违反本协议时，我们可能中止或终止向您提供服务。</p>
          </section>

          <section>
            <h2>适用法律与争议解决</h2>
            <p>
              本协议的订立、执行与解释均应适用<strong>中华人民共和国法律</strong>（不含冲突法）。
              因本协议引发的争议，双方应友好协商；协商不成的，任一方可向<strong>有管辖权的人民法院</strong>提起诉讼。
            </p>
          </section>

          <section>
            <h2>与我们联系</h2>
            <p>
              您对本协议或本平台服务有疑问的，可通过产品内配置的反馈渠道与我们联系；若暂未开放该渠道，
              请通过<strong>本项目部署方或指导老师</strong>指定的联系方式反馈意见（若为比赛/校内作品场景）。
            </p>
          </section>

          <p class="usa-footnote">
            对外产品名称为<strong>ThreshCore</strong>；文中所述日志、运维等措辞仅用于概括功能方向。
          </p>
        </article>
        <footer class="usa-footer">
          <div class="usa-footer-inner">
            <p v-if="!scrolledToEnd" class="usa-footer-hint">请向上滑动阅读全文至底部，再点击确认</p>
            <button
              type="button"
              class="usa-primary"
              :disabled="!scrolledToEnd"
              :title="scrolledToEnd ? '' : '请先滑动至文末'"
              @click="acknowledgeRead"
            >
              我已阅读并同意
            </button>
          </div>
        </footer>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { ref, watch, nextTick } from 'vue'

const props = defineProps({
  modelValue: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['update:modelValue', 'readConfirmed'])

const bodyEl = ref(null)
const scrolledToEnd = ref(false)

function checkScrollEnd() {
  const el = bodyEl.value
  if (!el) return
  const slack = 40
  scrolledToEnd.value = el.scrollTop + el.clientHeight >= el.scrollHeight - slack
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      scrolledToEnd.value = false
      nextTick(() => {
        const el = bodyEl.value
        if (el) {
          el.scrollTop = 0
          checkScrollEnd()
        }
      })
    }
  }
)

function closeWithoutConfirm() {
  emit('update:modelValue', false)
}

function acknowledgeRead() {
  if (!scrolledToEnd.value) return
  emit('readConfirmed')
  emit('update:modelValue', false)
}
</script>

<style scoped>
.usa-mask {
  position: fixed;
  inset: 0;
  z-index: 10000;
  background: rgba(15, 23, 42, 0.55);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  backdrop-filter: blur(4px);
}

.usa-drawer {
  width: min(680px, 100%);
  max-height: min(90vh, 820px);
  background: #fff;
  border-radius: 16px;
  box-shadow: 0 24px 80px rgba(0, 0, 0, 0.25);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.usa-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 18px;
  border-bottom: 1px solid #e2e8f0;
  flex-shrink: 0;
}

.usa-header h1 {
  flex: 1;
  margin: 0;
  font-size: 17px;
  font-weight: 700;
  color: #0f172a;
  text-align: center;
}

.usa-back {
  border: none;
  background: #f1f5f9;
  color: #334155;
  font-size: 14px;
  padding: 8px 12px;
  border-radius: 8px;
  cursor: pointer;
  font-weight: 600;
}

.usa-back:hover {
  background: #e2e8f0;
}

.usa-close {
  border: none;
  background: transparent;
  color: #64748b;
  font-size: 22px;
  line-height: 1;
  cursor: pointer;
  padding: 4px 8px;
}

.usa-close:hover {
  color: #0f172a;
}

.usa-meta {
  padding: 8px 20px;
  font-size: 12px;
  color: #64748b;
  border-bottom: 1px solid #f1f5f9;
  flex-shrink: 0;
}

.usa-body {
  flex: 1;
  overflow-y: auto;
  padding: 20px 22px 8px;
  font-size: 14px;
  line-height: 1.75;
  color: #334155;
}

.usa-body h2 {
  margin: 20px 0 10px;
  font-size: 15px;
  color: #0f172a;
  border-left: 3px solid #3b82f6;
  padding-left: 10px;
}

.usa-body section:first-child h2 {
  margin-top: 0;
}

.usa-body p {
  margin: 0 0 12px;
}

.usa-body ul,
.usa-body ol {
  margin: 0 0 12px;
  padding-left: 1.35em;
}

.usa-body li {
  margin-bottom: 6px;
}

.usa-footnote {
  margin-top: 24px;
  padding-top: 16px;
  border-top: 1px dashed #cbd5e1;
  font-size: 12px;
  color: #94a3b8;
}

.usa-footer {
  padding: 14px 20px 18px;
  border-top: 1px solid #e2e8f0;
  flex-shrink: 0;
}

.usa-footer-inner {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 10px;
  width: 100%;
}

.usa-footer-hint {
  margin: 0;
  width: 100%;
  text-align: right;
  font-size: 12px;
  line-height: 1.45;
  color: #64748b;
}

.usa-primary {
  border: none;
  background: #0d9488;
  color: #fff;
  font-weight: 600;
  padding: 10px 28px;
  border-radius: var(--ops-radius-sm, 6px);
  cursor: pointer;
  font-size: 14px;
}

.usa-primary:hover:not(:disabled) {
  background: #0f766e;
}

.usa-primary:disabled {
  opacity: 0.48;
  cursor: not-allowed;
  filter: none;
}

@media (max-width: 520px) {
  .usa-mask {
    padding: 12px;
  }

  .usa-drawer {
    border-radius: 12px;
    max-height: 92vh;
  }

  .usa-header h1 {
    font-size: 15px;
  }

  .usa-body {
    padding: 14px 16px 6px;
  }
}
</style>
