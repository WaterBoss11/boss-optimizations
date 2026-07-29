package dev.bossoptimizations.mixin;

import dev.bossoptimizations.booksafety.BookSafety;
import dev.bossoptimizations.booksafety.BookScanReport;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * The book safety gate.
 *
 * <p>{@code BookAccess.fromItem} is the single place the client turns a book item into readable
 * page content - a scan of every class in the 26.2 client jar found only this and
 * {@code BookSignScreen} referencing {@code WrittenBookContent} at all. Nothing on the tooltip
 * or inventory path touches page content, so gating here is both the enforcement point for the
 * size limits and the point that keeps parsing lazy: content is read on open and nowhere else.
 *
 * <p>On rejection the book is replaced with a single explanatory page. The hostile content is
 * never parsed into a renderable form.
 */
@Mixin(BookViewScreen.BookAccess.class)
public class BookAccessMixin {
	@Inject(
			method = "fromItem(Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/client/gui/screens/inventory/BookViewScreen$BookAccess;",
			at = @At("HEAD"),
			cancellable = true
	)
	private static void bossoptimizations$guardBookContent(ItemStack stack,
			CallbackInfoReturnable<BookViewScreen.BookAccess> cir) {
		BookScanReport report = BookSafety.scan(stack, false);

		if (report.isSafe()) {
			return;
		}

		BookSafety.logRejection(report);
		cir.setReturnValue(new BookViewScreen.BookAccess(List.of(BookSafety.warningPage(report))));
	}
}
