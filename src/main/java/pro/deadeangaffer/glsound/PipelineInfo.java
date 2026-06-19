package pro.deadeangaffer.glsound;

public record PipelineInfo(long id, String status, String ref, String webUrl, String etag) {

    public boolean isTerminal() {
        return switch (status) {
            case "success", "failed", "canceled", "skipped" -> true;
            default -> false;
        };
    }

    public boolean isSuccess() { return "success".equals(status); }

    public boolean isFailure() { return "failed".equals(status); }
}
