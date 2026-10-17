package dev.tongt.mcserverauth.flow;

import dev.tongt.mcserverauth.config.Messages;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Builds every dialog the plugin shows. Button actions are the constants below. */
public final class DialogFactory {
    public static final String SUBMIT = "submit";
    public static final String CANCEL = "cancel";
    public static final String FORGOT = "forgot";
    public static final String SEND_CODE = "send_code";
    public static final String HAVE_CODE = "have_code";
    public static final String RESEND = "resend";
    public static final String BACK = "back";

    private static final int INPUT_WIDTH = 250;
    private static final int BUTTON_WIDTH = 120;
    private static final int EMAIL_MAX_LENGTH = 254;
    private static final int CODE_LENGTH = 6;

    private final Messages messages;
    private final int passwordMaxLength;

    public DialogFactory(Messages messages, int passwordMaxLength) {
        this.messages = messages;
        this.passwordMaxLength = passwordMaxLength;
    }

    public Dialog serverPassword(@Nullable Component error) {
        return build("dialog.server-password.title", body("dialog.server-password.body", error),
                List.of(password("password", "input.password")),
                List.of(button("button.submit", SUBMIT), button("button.cancel", CANCEL)), false);
    }

    public Dialog register(@Nullable Component error) {
        return build("dialog.register.title", body("dialog.register.body", error),
                List.of(password("password", "input.password"), password("confirm", "input.confirm"),
                        text("email", "input.email", EMAIL_MAX_LENGTH)),
                List.of(button("button.submit", SUBMIT), button("button.cancel", CANCEL)), false);
    }

    public Dialog login(@Nullable Component error) {
        return build("dialog.login.title", body("dialog.login.body", error),
                List.of(password("password", "input.password")),
                List.of(button("button.login", SUBMIT), button("button.forgot", FORGOT), button("button.cancel", CANCEL)),
                false);
    }

    public Dialog forgotMenu(String maskedEmail, @Nullable Component error) {
        return build("dialog.forgot.title", body("dialog.forgot.body", error, email(maskedEmail)),
                List.of(),
                List.of(button("button.send-code", SEND_CODE), button("button.have-code", HAVE_CODE),
                        button("button.back", BACK)),
                false);
    }

    /** Code entry for email verification (reset = false) or password reset (reset = true). */
    public Dialog code(boolean reset, String maskedEmail, @Nullable Component error) {
        String title = reset ? "dialog.reset-code.title" : "dialog.verify-email.title";
        String bodyKey = reset ? "dialog.reset-code.body" : "dialog.verify-email.body";
        return build(title, body(bodyKey, error, email(maskedEmail)),
                List.of(text("code", "input.code", CODE_LENGTH)),
                List.of(button("button.confirm", SUBMIT), button("button.resend", RESEND), button("button.back", BACK),
                        button("button.cancel", CANCEL)),
                false);
    }

    /** New password + confirm. Used for reset, and by admins to set the server password. */
    public Dialog newPassword(String titleKey, @Nullable Component error, boolean closable) {
        return build(titleKey, error == null ? List.of() : List.of(DialogBody.plainMessage(error)),
                List.of(password("password", "input.new-password"), password("confirm", "input.confirm")),
                List.of(button("button.submit", SUBMIT), button("button.cancel", CANCEL)), closable);
    }

    public Dialog changePassword(@Nullable Component error) {
        return build("dialog.change-password.title", error == null ? List.of() : List.of(DialogBody.plainMessage(error)),
                List.of(password("current", "input.current-password"), password("password", "input.new-password"),
                        password("confirm", "input.confirm")),
                List.of(button("button.submit", SUBMIT), button("button.cancel", CANCEL)), true);
    }

    private Dialog build(String titleKey, List<DialogBody> body, List<DialogInput> inputs,
                         List<ActionButton> buttons, boolean closable) {
        DialogBase base = DialogBase.builder(messages.get(titleKey))
                .canCloseWithEscape(closable)
                .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
                .body(body)
                .inputs(inputs)
                .build();
        // Four buttons read better as a 2 x 2 grid than as a row of three plus one.
        int columns = buttons.size() == 4 ? 2 : Math.min(buttons.size(), 3);
        DialogType type = DialogType.multiAction(buttons).columns(columns).build();
        return Dialog.create(factory -> factory.empty().base(base).type(type));
    }

    private List<DialogBody> body(String key, @Nullable Component error, TagResolver... resolvers) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(messages.get(key, resolvers)));
        if (error != null) {
            body.add(DialogBody.plainMessage(error));
        }
        return body;
    }

    private static TagResolver email(String maskedEmail) {
        return Placeholder.unparsed("email", maskedEmail);
    }

    private DialogInput password(String id, String labelKey) {
        return text(id, labelKey, passwordMaxLength);
    }

    private DialogInput text(String id, String labelKey, int maxLength) {
        return DialogInput.text(id, messages.get(labelKey)).width(INPUT_WIDTH).maxLength(maxLength).build();
    }

    private ActionButton button(String labelKey, String action) {
        return ActionButton.builder(messages.get(labelKey))
                .width(BUTTON_WIDTH)
                .action(DialogAction.customClick(Key.key(DialogWaiter.NAMESPACE, action), null))
                .build();
    }
}
