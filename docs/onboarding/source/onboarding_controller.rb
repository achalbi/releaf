class OnboardingController < BrowserController
  before_action :require_authenticated_user!, only: :dismiss

  def show
    @signed_in = user_signed_in?
  end

  def dismiss
    unless User.column_names.include?("onboarding_completed_at")
      Rails.logger.warn(
        event: "onboarding.dismiss.skipped_missing_column",
        user_id: current_user.id
      )
      head :no_content
      return
    end

    User.where(id: current_user.id).update_all(onboarding_completed_at: Time.current)
    head :no_content
  end
end
