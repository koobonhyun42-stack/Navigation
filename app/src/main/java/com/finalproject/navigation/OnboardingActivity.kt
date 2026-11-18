package com.finalproject.navigation

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import com.finalproject.navigation.databinding.ActivityOnboardingBinding

// 처음 실행할 때 보여 주는 안내 화면 (3쪽)
class OnboardingActivity : AppCompatActivity() {
    private lateinit var binding: ActivityOnboardingBinding
    private var page = 0

    private val titles = intArrayOf(
        R.string.onb_title_1, R.string.onb_title_2, R.string.onb_title_3
    )
    private val bodies = intArrayOf(
        R.string.onb_body_1, R.string.onb_body_2, R.string.onb_body_3
    )

    // 설정에서 "다시 보기"로 들어온 경우 끝나면 그냥 닫음
    private val isReview get() = intent.getBooleanExtra(EXTRA_REVIEW, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        page = savedInstanceState?.getInt(KEY_PAGE, 0) ?: 0
        ViewCompat.setAccessibilityHeading(binding.tvTitle, true)

        binding.btnNext.setOnClickListener {
            if (page < titles.lastIndex) {
                page++
                showPage()
            } else {
                finishOnboarding()
            }
        }
        // 이전 쪽으로 돌아가기
        binding.btnPrev.setOnClickListener {
            if (page > 0) {
                page--
                showPage()
            }
        }
        binding.btnSkip.setOnClickListener { finishOnboarding() }
        showPage()
    }

    private fun showPage() {
        binding.tvStep.text = getString(R.string.onb_step, page + 1, titles.size)
        binding.tvTitle.setText(titles[page])
        binding.tvBody.setText(bodies[page])
        val last = page == titles.lastIndex
        binding.btnNext.setText(if (last) R.string.onb_start else R.string.onb_next)
        // 첫 쪽에는 [이전]이 없고, 마지막 쪽에는 [건너뛰기]가 없음 (없는 쪽은 옆 버튼이 넓어짐)
        binding.btnPrev.visibility = if (page == 0) View.GONE else View.VISIBLE
        binding.btnSkip.visibility = if (last) View.GONE else View.VISIBLE
        // 마지막 쪽에서는 [이전] 왼쪽 여백이 필요 없음
        (binding.btnSkip.layoutParams as android.view.ViewGroup.MarginLayoutParams).marginStart =
            if (page == 0) 0 else (12 * resources.displayMetrics.density).toInt()
        binding.btnSkip.requestLayout()
        // 쪽이 바뀌면 설명을 맨 위부터 보이게 함
        binding.onbScroll.scrollTo(0, 0)
        // 쪽이 바뀌면 톡백 초점을 제목으로 옮겨서 처음부터 읽게 함
        binding.tvTitle.post {
            binding.tvTitle.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        }
    }

    private fun finishOnboarding() {
        UiSettings.setOnboarded(this, true)
        if (!isReview) startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_PAGE, page)
        super.onSaveInstanceState(outState)
    }

    companion object {
        const val EXTRA_REVIEW = "review"
        private const val KEY_PAGE = "page"
    }
}
